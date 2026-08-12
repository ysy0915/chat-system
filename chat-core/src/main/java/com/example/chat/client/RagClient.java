package com.example.chat.client;

import com.example.chat.dto.LLMMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * RAG 客户端 —— 通过内部 HTTP 调用 chat-llm 的 /internal/rag/* 端点
 * （知识库检索 / 对话记忆 / 向量化 / RAG 回答）。
 *
 * <p>旧版 RAG 运行时已迁移至 chat-llm，chat-core 不再直连 Milvus/MySQL 知识库，
 * 统一通过本客户端访问。
 */
@Component
public class RagClient {

    private static final Logger log = LoggerFactory.getLogger(RagClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            1, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Value("${app.llm-service.base-url:http://127.0.0.1:9095}")
    private String baseUrl;

    public RagClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ──────────── 知识库检索 ────────────────────────────

    /**
     * 按知识库 ID 语义检索，返回命中的文档分片。
     */
    public List<SearchResult> search(long kbId, String query, int topK) {
        try {
            Map<String, Object> body = Map.of("kbId", kbId, "query", query, "topK", topK);
            Map<String, Object> resp = postJson("/internal/rag/search", body);
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                log.warn("[RagClient] search 失败 kb={} resp={}", kbId, resp);
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) resp.get("results");
            if (results == null) return List.of();
            List<SearchResult> list = new ArrayList<>(results.size());
            for (Map<String, Object> r : results) {
                list.add(new SearchResult(
                        (String) r.get("text"),
                        (String) r.get("source"),
                        ((Number) r.get("docId")).longValue(),
                        ((Number) r.get("score")).floatValue()));
            }
            return list;
        } catch (Exception e) {
            log.warn("[RagClient] search 异常 kb={} err={}", kbId, e.getMessage());
            return List.of();
        }
    }

    // ──────────── 文本向量化 ────────────────────────────

    /**
     * 单文本向量化（chat-llm 新版 Embedding 服务）。
     */
    public float[] embed(String text) {
        try {
            Map<String, Object> resp = postJson("/internal/rag/embed", Map.of("text", text));
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                log.warn("[RagClient] embed 失败");
                return new float[0];
            }
            @SuppressWarnings("unchecked")
            List<Number> vec = (List<Number>) resp.get("vector");
            if (vec == null || vec.isEmpty()) return new float[0];
            float[] arr = new float[vec.size()];
            for (int i = 0; i < vec.size(); i++) {
                arr[i] = vec.get(i).floatValue();
            }
            return arr;
        } catch (Exception e) {
            log.warn("[RagClient] embed 异常: {}", e.getMessage());
            return new float[0];
        }
    }

    // ──────────── 对话记忆 ──────────────────────────────

    /**
     * 异步保存一轮对话记忆（fire-and-forget，不阻塞业务线程）。
     */
    public void saveMemoryAsync(String scene, Long userId, String question, String answer) {
        if (question == null || question.isBlank()) return;
        asyncExecutor.submit(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("scene", scene);
                body.put("userId", userId);
                body.put("question", question);
                body.put("answer", answer != null ? answer : "");
                postJson("/internal/rag/memory/save", body);
            } catch (Exception e) {
                log.debug("[RagClient] 记忆保存失败 scene={} user={}: {}", scene, userId, e.getMessage());
            }
        });
    }

    /**
     * 构建记忆上下文（短期 Redis + 长期 Milvus），无记忆时返回 null。
     */
    public String memoryContext(String scene, Long userId, String question) {
        try {
            Map<String, Object> body = Map.of("scene", scene, "userId", userId, "question", question);
            Map<String, Object> resp = postJson("/internal/rag/memory/context", body);
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                return null;
            }
            Object memory = resp.get("memory");
            if (memory == null || String.valueOf(memory).isBlank()) return null;
            return String.valueOf(memory);
        } catch (Exception e) {
            log.debug("[RagClient] 记忆上下文获取失败 scene={} user={}: {}", scene, userId, e.getMessage());
            return null;
        }
    }

    // ──────────── 长期事实记忆（L2：user_memory） ────────────

    /**
     * 异步保存用户关键事实（LLM 抽取 + 向量化存 Milvus user_memory，fire-and-forget）。
     */
    public void saveFactsAsync(String scene, Long userId, String question, String answer) {
        if (userId == null || question == null || question.isBlank()) return;
        asyncExecutor.submit(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("scene", scene);
                body.put("userId", userId);
                body.put("question", question);
                body.put("answer", answer != null ? answer : "");
                postJson("/internal/rag/memory/facts/save", body);
            } catch (Exception e) {
                log.debug("[RagClient] 事实记忆保存失败 scene={} user={}: {}", scene, userId, e.getMessage());
            }
        });
    }

    /**
     * 语义召回用户相关事实（供注入 System Prompt），失败返回空列表。
     */
    public List<String> recallFacts(Long userId, String question, int topK) {
        try {
            Map<String, Object> body = Map.of("userId", userId, "question", question, "topK", topK);
            Map<String, Object> resp = postJson("/internal/rag/memory/facts/recall", body);
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                return List.of();
            }
            @SuppressWarnings("unchecked")
            List<Object> facts = (List<Object>) resp.get("facts");
            if (facts == null || facts.isEmpty()) return List.of();
            return facts.stream().map(String::valueOf).toList();
        } catch (Exception e) {
            log.debug("[RagClient] 事实记忆召回失败 user={}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    // ──────────── RAG 回答（非流式） ────────────────────

    /**
     * 知识库 RAG 检索 + LLM 回答（非流式）。失败返回 null（调用方降级）。
     */
    public String ragInvoke(long kbId, String question, List<LLMMessage> messages,
                            double temperature, String provider, String model) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kbId", kbId);
            body.put("question", question);
            body.put("messages", toMapMessages(messages));
            body.put("temperature", temperature);
            body.put("provider", provider);
            body.put("model", model);
            Map<String, Object> resp = postJson("/internal/rag/invoke", body);
            if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
                log.warn("[RagClient] ragInvoke 失败 kb={} resp={}", kbId, resp);
                return null;
            }
            return (String) resp.getOrDefault("content", "");
        } catch (Exception e) {
            log.warn("[RagClient] ragInvoke 异常 kb={}: {}", kbId, e.getMessage());
            return null;
        }
    }

    // ──────────── RAG 回答（SSE 流式） ──────────────────

    /**
     * 知识库 RAG 检索 + LLM 流式回答（SSE）。
     *
     * @param callback 逐 token 回调
     * @return 完整回答内容（流式消费后累积），失败返回 null
     */
    public String ragInvokeStream(long kbId, String question, List<LLMMessage> messages,
                                  double temperature, String provider, String model,
                                  Consumer<String> callback) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("kbId", kbId);
            body.put("question", question);
            body.put("messages", toMapMessages(messages));
            body.put("temperature", temperature);
            body.put("provider", provider);
            body.put("model", model);
            String json = objectMapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/internal/rag/invoke-stream"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            CompletableFuture<HttpResponse<java.util.stream.Stream<String>>> future =
                    httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofLines());

            HttpResponse<java.util.stream.Stream<String>> resp = future.get(180, TimeUnit.SECONDS);
            if (resp.statusCode() != 200) {
                log.warn("[RagClient] ragInvokeStream HTTP {}", resp.statusCode());
                return null;
            }

            StringBuilder collected = new StringBuilder();
            try (java.util.stream.Stream<String> lines = resp.body()) {
                lines.forEach(line -> {
                    if (line == null) return;
                    if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if (!payload.isEmpty() && !"[DONE]".equals(payload)) {
                            collected.append(payload);
                            if (callback != null) callback.accept(payload);
                        }
                    }
                });
            }
            return collected.length() > 0 ? collected.toString() : null;
        } catch (Exception e) {
            log.warn("[RagClient] ragInvokeStream 异常 kb={}: {}", kbId, e.getMessage());
            return null;
        }
    }

    // ──────────── 内部方法 ──────────────────────────────

    private List<Map<String, String>> toMapMessages(List<LLMMessage> messages) {
        List<Map<String, String>> list = new ArrayList<>();
        if (messages != null) {
            for (LLMMessage m : messages) {
                Map<String, String> mm = new LinkedHashMap<>();
                mm.put("role", m.getRole() != null ? m.getRole() : "user");
                mm.put("content", m.getContent() != null ? String.valueOf(m.getContent()) : "");
                list.add(mm);
            }
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            log.warn("[RagClient] {} HTTP {} body={}", path, resp.statusCode(),
                    resp.body().length() > 300 ? resp.body().substring(0, 300) : resp.body());
            return null;
        }
        return objectMapper.readValue(resp.body(), Map.class);
    }

    // ──────────── DTO ───────────────────────────────────

    public record SearchResult(String text, String source, long docId, float score) {}
}
