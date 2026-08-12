package com.example.chat.llm.rag.controller;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.rag.legacy.ConversationMemoryService;
import com.example.chat.llm.rag.legacy.LegacyEmbeddingService;
import com.example.chat.llm.rag.legacy.LegacyVectorStoreService;
import com.example.chat.llm.service.LLMInvokeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 旧版 RAG 运行时内部 API —— 供 chat-core 跨进程调用（知识库检索 / 对话记忆 / 向量化 / RAG 回答）。
 *
 * <p>端点路径为 /internal/rag/*，仅限服务间内网访问，不做外部鉴权。
 */
@RestController
@RequestMapping("/internal/rag")
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class LegacyRagController {

    private static final Logger log = LoggerFactory.getLogger(LegacyRagController.class);

    private final LegacyVectorStoreService vectorStoreService;
    private final ConversationMemoryService memoryService;
    private final LegacyEmbeddingService embeddingService;
    private final LLMInvokeService llmInvokeService;

    private final ExecutorService streamExecutor = new ThreadPoolExecutor(
            2, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Value("${app.rag.search.top-k:5}")
    private int topK;

    @Value("${app.rag.search.score-threshold:0.3}")
    private float scoreThreshold;

    @Value("${app.rag.context.max-chars:3000}")
    private int maxContextChars;

    public LegacyRagController(LegacyVectorStoreService vectorStoreService,
                               ConversationMemoryService memoryService,
                               LegacyEmbeddingService embeddingService,
                               LLMInvokeService llmInvokeService) {
        this.vectorStoreService = vectorStoreService;
        this.memoryService = memoryService;
        this.embeddingService = embeddingService;
        this.llmInvokeService = llmInvokeService;
    }

    // ──────────── 知识库检索 ────────────────────────────

    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> req) {
        Long kbId = ((Number) req.get("kbId")).longValue();
        String query = (String) req.get("query");
        int k = req.get("topK") != null ? ((Number) req.get("topK")).intValue() : topK;

        List<LegacyVectorStoreService.SearchResult> results = vectorStoreService.search(kbId, query, k);
        List<Map<String, Object>> list = results.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", r.text);
            m.put("source", r.source);
            m.put("docId", r.docId);
            m.put("score", r.score);
            return m;
        }).toList();
        return Map.of("success", true, "results", list, "total", list.size());
    }

    // ──────────── 文本向量化 ────────────────────────────

    @PostMapping("/embed")
    public Map<String, Object> embed(@RequestBody Map<String, Object> req) {
        String text = (String) req.get("text");
        if (text == null || text.isBlank()) {
            return Map.of("success", false, "error", "text 不能为空");
        }
        float[] vector = embeddingService.embed(text);
        if (vector.length == 0) {
            return Map.of("success", false, "error", "向量化失败");
        }
        List<Float> list = new ArrayList<>(vector.length);
        for (float v : vector) list.add(v);
        return Map.of("success", true, "vector", list);
    }

    // ──────────── 对话记忆 ──────────────────────────────

    @PostMapping("/memory/save")
    public Map<String, Object> saveMemory(@RequestBody Map<String, Object> req) {
        String scene = (String) req.get("scene");
        Long userId = ((Number) req.get("userId")).longValue();
        String question = (String) req.get("question");
        String answer = (String) req.get("answer");
        try {
            memoryService.saveConversation(scene, userId, question, answer);
            return Map.of("success", true);
        } catch (Exception e) {
            log.warn("[Memory] 内部保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    @PostMapping("/memory/context")
    public Map<String, Object> memoryContext(@RequestBody Map<String, Object> req) {
        String scene = (String) req.get("scene");
        Long userId = ((Number) req.get("userId")).longValue();
        String question = (String) req.get("question");
        String memory = memoryService.buildMemoryContext(scene, userId, question);
        return Map.of("success", true, "memory", memory);
    }

    // ──────────── RAG 回答（非流式） ────────────────────

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@RequestBody Map<String, Object> req) {
        try {
            LangChainRequest lmReq = buildRagRequest(req);
            LangChainResponse resp = llmInvokeService.invoke(lmReq);
            return Map.of(
                    "success", resp.isSuccess(),
                    "content", resp.getContent() != null ? resp.getContent() : "",
                    "provider", resp.getProvider() != null ? resp.getProvider() : "",
                    "model", resp.getModel() != null ? resp.getModel() : "",
                    "error", resp.getError() != null ? resp.getError() : ""
            );
        } catch (Exception e) {
            log.error("[RAG] 内部 invoke 失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ──────────── RAG 回答（SSE 流式） ──────────────────

    @PostMapping(value = "/invoke-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter invokeStream(@RequestBody Map<String, Object> req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        streamExecutor.submit(() -> {
            try {
                LangChainRequest lmReq = buildRagRequest(req);
                llmInvokeService.invokeStream(lmReq,
                        chunk -> safeSend(emitter, chunk),
                        () -> {
                            try {
                                emitter.send(SseEmitter.event().name("done").data(""));
                                emitter.complete();
                            } catch (IOException e) {
                                log.debug("[RAG] SSE 发送 done 失败: {}", e.getMessage());
                            }
                        },
                        err -> emitter.completeWithError(err));
            } catch (Exception e) {
                log.error("[RAG] 内部 invoke-stream 失败", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void safeSend(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(chunk));
        } catch (IOException e) {
            log.debug("[RAG] SSE 发送失败: {}", e.getMessage());
        }
    }

    // ──────────── 内部方法 ──────────────────────────────

    @SuppressWarnings("unchecked")
    private LangChainRequest buildRagRequest(Map<String, Object> req) {
        Long kbId = req.get("kbId") != null ? ((Number) req.get("kbId")).longValue() : null;
        String question = (String) req.getOrDefault("question", "");
        Double temperature = req.get("temperature") != null ? ((Number) req.get("temperature")).doubleValue() : null;
        String provider = (String) req.getOrDefault("provider", "qwen");
        String model = (String) req.getOrDefault("model", "qwen-plus");
        Integer k = req.get("topK") != null ? ((Number) req.get("topK")).intValue() : topK;
        List<Map<String, Object>> messages = (List<Map<String, Object>>) req.get("messages");
        if (messages == null) messages = List.of();

        List<Map<String, Object>> ragMessages = new ArrayList<>(messages);
        if (kbId != null && question != null && !question.isBlank()) {
            String context = retrieveContext(kbId, question, k);
            if (context != null && !context.isBlank()) {
                ragMessages.add(0, Map.of("role", "system", "content", buildRAGSystemPrompt(context)));
            }
        }

        LangChainRequest lmReq = new LangChainRequest();
        lmReq.setBizType("RAG");
        lmReq.setProvider(provider);
        lmReq.setModel(model);
        lmReq.setMessages(ragMessages);
        if (temperature != null) lmReq.setTemperature(temperature);
        return lmReq;
    }

    private String retrieveContext(Long kbId, String query, int k) {
        List<LegacyVectorStoreService.SearchResult> results = vectorStoreService.search(kbId, query, k);
        if (results.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int totalChars = 0;
        for (LegacyVectorStoreService.SearchResult r : results) {
            if (r.score < scoreThreshold) continue;
            if (totalChars + r.text.length() > maxContextChars) break;
            sb.append("--- 来源: ").append(r.source).append(" (相似度: ")
              .append(String.format("%.2f", r.score)).append(") ---\n");
            sb.append(r.text).append("\n\n");
            totalChars += r.text.length();
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private String buildRAGSystemPrompt(String context) {
        return "你是一个知识库问答助手。请根据以下检索到的参考资料回答用户问题。\n" +
               "如果参考资料中没有相关信息，请坦诚告知，不要编造。\n" +
               "回答时可以引用资料来源。\n\n" +
               "【参考资料】\n" + context;
    }
}
