package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.intent.funnel.ThinkingStreamParser;
import com.example.chat.repository.TreeHoleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 树洞核心服务（门面）
 * 负责流式对话、同步对话、文件处理、重新生成、停止控制。
 * 模型配置解析 → TreeHoleModelConfigResolver
 * 历史构建/记忆 → TreeHoleHistoryBuilder
 * 历史查询     → TreeHoleQueryService
 */
@Service
public class TreeHoleService {

    private static final Logger log = LoggerFactory.getLogger(TreeHoleService.class);

    private final TreeHoleRepository treeHoleRepository;
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;
    private final BroadcastService broadcastService;
    private final LLMInvoker llmInvoker;
    private final StreamStopManager streamStopManager;
    private final LlmConfigProperties llmConfig;
    private final TreeHoleModelConfigResolver modelConfigResolver;
    private final TreeHoleHistoryBuilder historyBuilder;

    // --- 可选注入 ---

    @Autowired(required = false)
    private com.example.chat.rag.service.RAGService ragService;

    @Autowired(required = false)
    private com.example.chat.langchain4j.LangChain4jTreeHoleService langChain4jTreeHoleService;

    @org.springframework.beans.factory.annotation.Value("${app.langchain4j.treehole.enabled:false}")
    private boolean langChain4jEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.rag.treehole.kb-id:0}")
    private long treeholeKbId;

    /** 树洞异步流式调用线程池 */
    private final ExecutorService treeholeExecutor =
            ThreadPoolFactory.create(3, 10, 30, "treehole-worker");

    public TreeHoleService(TreeHoleRepository treeHoleRepository,
                           RateLimitService rateLimitService,
                           ObjectMapper objectMapper,
                           BroadcastService broadcastService,
                           LLMInvoker llmInvoker,
                           StreamStopManager streamStopManager,
                           LlmConfigProperties llmConfig,
                           TreeHoleModelConfigResolver modelConfigResolver,
                           TreeHoleHistoryBuilder historyBuilder) {
        this.treeHoleRepository = treeHoleRepository;
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.llmInvoker = llmInvoker;
        this.streamStopManager = streamStopManager;
        this.llmConfig = llmConfig;
        this.modelConfigResolver = modelConfigResolver;
        this.historyBuilder = historyBuilder;
    }

    // ──────────────── 停止控制 ────────────────

    public void requestStop(String reqId) {
        streamStopManager.requestStop(reqId);
    }

    public boolean isStopped(String reqId) {
        return streamStopManager.isStopped(reqId);
    }

    // ──────────────── 带文件提问 ────────────────

    /**
     * 带文件的树洞请求：图片 → qwen-vl-max 视觉解析；其他 → 智谱文本解析
     */
    public TreeHoleMessage askWithFile(Long userId, String question, String mood,
                                       String fileName, byte[] fileBytes) {
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            throw new com.example.chat.exception.ChatServiceException("treehole", "RATE_LIMITED",
                    "发送太频繁，请 " + retry + " 秒后再试");
        }

        String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        boolean isImage = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                || lowerName.endsWith(".png") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".webp");

        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = (question != null ? question : "") + (fileName != null ? " 📎 " + fileName : "");
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        try {
            String answer = isImage
                    ? handleImageFile(question, mood, fileName, fileBytes)
                    : handleTextFile(question, mood, fileName, fileBytes);
            m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            m.status = "done";
            m.tokens = Math.max(1, (answer != null ? answer.length() : 0) / 2);
        } catch (Exception e) {
            log.error("TreeHole 文件解析失败: {}", e.getMessage());
            Throwable root = e.getCause() != null ? e.getCause() : e;
            String errMsg = root.getMessage() != null ? root.getMessage() : e.getMessage();
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", "解析失败：" + errMsg));
            } catch (JsonProcessingException je) {
                m.answerJson = "{\"answer\":\"解析失败：未知错误\"}";
            }
            m.status = "error";
        }

        treeHoleRepository.updateByReqId(m);

        if ("done".equals(m.status)) {
            historyBuilder.saveMemoryIfAvailable(userId, question, m.answerJson);
        }
        return m;
    }

    /** 图片文件：用 qwen-vl-max 多模态解析 */
    private String handleImageFile(String question, String mood, String fileName, byte[] fileBytes) throws Exception {
        ModelConfig config = modelConfigResolver.resolveImageParseOrThrow();

        String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        String mimeType = lowerName.endsWith(".png") ? "image/png"
                : lowerName.endsWith(".gif") ? "image/gif"
                : lowerName.endsWith(".webp") ? "image/webp"
                : "image/jpeg";

        String fileBase64 = Base64.getEncoder().encodeToString(fileBytes);
        String imageQuestion = (question != null && !question.isBlank()) ? question : "请描述这张图片的内容";
        if (mood != null && !mood.isBlank()) {
            imageQuestion = "[情绪：" + mood + "] " + imageQuestion;
        }

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(TreeHoleHistoryBuilder.SYSTEM_PROMPT
                + "\n\n如果用户上传了图片，请结合图片内容给予温暖的情感陪伴与回应。"));
        messages.add(LLMMessage.userWithImage(imageQuestion, fileBase64, mimeType));

        log.info("TreeHole 图片解析 model={}", config.model);
        return llmInvoker.invoke(config, messages, 0.85, "treehole", llmConfig.getBaseUrl(), llmConfig.getApiKey());
    }

    /** 非图片文件：提取文本内容，用智谱解析 */
    private String handleTextFile(String question, String mood, String fileName, byte[] fileBytes) throws Exception {
        ModelConfig zhipu = modelConfigResolver.resolveZhipuOrThrow();

        String fileText = "";
        String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
        if (fileBytes != null && fileBytes.length > 0) {
            if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".json") ||
                lowerName.endsWith(".log") || lowerName.endsWith(".xml")) {
                fileText = new String(fileBytes, StandardCharsets.UTF_8);
                if (fileText.length() > 8000) fileText = fileText.substring(0, 8000) + "\n...(内容已截断)";
            } else {
                fileText = "[已上传文件: " + fileName + "，共 " + fileBytes.length / 1024 + " KB]";
            }
        }

        String lowerQ = question != null ? question.toLowerCase(Locale.ROOT) : "";
        boolean genDoc = lowerQ.contains("生成文档") || lowerQ.contains("生成word") || lowerQ.contains("生成报告");
        boolean genPpt = lowerQ.contains("生成ppt") || lowerQ.contains("生成幻灯片") || lowerQ.contains("做ppt");

        String systemPrompt;
        if (genPpt) {
            systemPrompt = "你是专业PPT内容策划师。请根据提供的材料，生成一份结构完整的PPT大纲，" +
                    "包含标题页、目录、各章节要点（每页3~5条），以及结尾页。用Markdown格式输出，" +
                    "每个章节用 ## 标注，每条要点用 - 开头。结尾加「供您参考」。";
        } else if (genDoc) {
            systemPrompt = "你是专业文档撰写助手。请根据提供的材料，生成一份结构完整、逻辑清晰的文档，" +
                    "包含标题、摘要、正文各节（用 ## 标注）以及结语。结尾加「供您参考」。";
        } else {
            systemPrompt = TreeHoleHistoryBuilder.SYSTEM_PROMPT
                    + "\n\n请结合用户上传的文件内容进行温暖的情感陪伴与回应。";
        }

        String userContent = question != null && !question.isBlank() ? question : "请解析这份文件";
        if (!fileText.isBlank()) userContent += "\n\n【文件内容】\n" + fileText;
        if (mood != null && !mood.isBlank()) userContent = "[情绪：" + mood + "] " + userContent;

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(systemPrompt));
        messages.add(LLMMessage.user(userContent));

        return llmInvoker.invoke(zhipu, messages, 0.85, "treehole", llmConfig.getBaseUrl(), llmConfig.getApiKey());
    }

    // ──────────────── 流式对话 ────────────────

    /**
     * 流式版本：发送情绪内容，逐 token 通过 WebSocket 推送给前端
     * 推送 topic: /topic/treehole.{userId}
     */
    public void askAndStream(Long userId, String question, String mood) {
        log.info("TreeHole askAndStream userId={} question={}", userId, question);
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            broadcastService.broadcast("/topic/treehole." + userId,
                    WsMessage.error("发送太频繁，请 " + retry + " 秒后再试").toMap());
            return;
        }

        // 构建历史上下文
        TreeHoleHistoryBuilder.HistoryContext ctx = historyBuilder.build(userId, question);
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(ctx.systemPrompt()
                + "\n\n如果问题复杂需要分析，先把分析推理写在 <thinking>...</thinking> 标签中，再给出最终回应。简单问题直接回应。"));
        messages.addAll(ctx.messages());

        String fullQuestion = (mood != null && !mood.isBlank())
                ? "[情绪：" + mood + "] " + question : question;
        messages.add(LLMMessage.user(fullQuestion));

        // 保存记录
        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = question;
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        ModelConfig config = modelConfigResolver.resolveMainModel();
        String effectiveApiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : llmConfig.getApiKey();

        final String reqId = m.reqId;
        final Long fUserId = userId;

        broadcastService.broadcast("/topic/treehole." + fUserId,
                WsMessage.of(WsMessage.TYPE_STREAM_START).withReqId(reqId).toMap());

        final long startTime = System.currentTimeMillis();
        treeholeExecutor.submit(() -> executeStreamCall(config, effectiveApiKey, messages,
                reqId, fUserId, question, m, startTime));
    }

    /** 流式调用核心逻辑 */
    private void executeStreamCall(ModelConfig config, String effectiveApiKey,
                                    List<LLMMessage> messages, String reqId,
                                    Long fUserId, String question, TreeHoleMessage m, long startTime) {
        StringBuilder answerCollector = new StringBuilder();
        String topic = "/topic/treehole." + fUserId;
        ThinkingStreamParser parser = new ThinkingStreamParser(
                t -> broadcastService.broadcast(topic,
                        WsMessage.thinkingToken(t).withReqId(reqId).toMap()),
                t -> {
                    answerCollector.append(t);
                    broadcastService.broadcast(topic,
                            WsMessage.streamToken(t).withReqId(reqId).toMap());
                },
                () -> broadcastService.broadcast(topic,
                        WsMessage.thinkingStart().withReqId(reqId).toMap())
        );

        try {
            String rawAnswer;
            if (ragService != null && treeholeKbId > 0) {
                rawAnswer = ragService.invokeWithRAGStream(config, treeholeKbId, question, messages,
                        0.85, "treehole", llmConfig.getBaseUrl(), effectiveApiKey,
                        token -> parser.feed(token));
            } else {
                rawAnswer = llmInvoker.invokeStream(config, messages, 0.85, "treehole",
                        llmConfig.getBaseUrl(), effectiveApiKey,
                        token -> parser.feed(token));
            }
            parser.flush();

            // 使用只含回答部分的文本（不含思考过程）
            String answer = (!answerCollector.isEmpty())
                    ? answerCollector.toString()
                    : (rawAnswer != null ? rawAnswer : "");

            long latency = System.currentTimeMillis() - startTime;
            int estimatedTokens = Math.max(1, (answer != null ? answer.length() : 0) / 2);

            m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            m.status = isStopped(reqId) ? "stopped" : "done";
            m.provider = config.provider;
            m.model = config.model;
            m.tokens = estimatedTokens;
            treeHoleRepository.updateByReqId(m);

            if (isStopped(reqId)) {
                broadcastService.broadcast(topic,
                        WsMessage.stopped(answer).withReqId(reqId).toMap());
            } else {
                broadcastService.broadcast(topic,
                        WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId)
                                .with("answer", answer).with("latency", latency)
                                .with("tokens", estimatedTokens).toMap());
                historyBuilder.saveMemoryIfAvailable(fUserId, question, m.answerJson);
            }
        } catch (Exception e) {
            log.error("TreeHole 流式调用失败: {}", e.getMessage(), e);
            m.answerJson = "{\"answer\":\"树洞暂时出了点小问题，请稍后再试...\"}";
            m.status = "error";
            treeHoleRepository.updateByReqId(m);
            broadcastService.broadcast(topic,
                    WsMessage.error("生成失败: " + e.getMessage()).withReqId(reqId).toMap());
        } finally {
            streamStopManager.remove(reqId);
        }
    }

    private void sendToken(Long userId, String reqId, String token) {
        if (streamStopManager.getOrDefault(reqId).get()) return;
        broadcastService.broadcast("/topic/treehole." + userId,
                WsMessage.streamToken(token).withReqId(reqId).toMap());
    }

    // ──────────────── 重新生成 ────────────────

    public void regenerate(String oldReqId, Long userId) {
        TreeHoleMessage original = treeHoleRepository.findByReqId(oldReqId);
        if (original == null) {
            broadcastService.broadcast("/topic/treehole." + userId,
                    WsMessage.error("原始消息不存在，无法重新生成").toMap());
            return;
        }
        if (original.question == null || original.question.isBlank()) {
            broadcastService.broadcast("/topic/treehole." + userId,
                    WsMessage.error("原始问题为空，无法重新生成").toMap());
            return;
        }
        log.info("TreeHole regenerate oldReqId={} userId={}", oldReqId, userId);
        askAndStream(userId, original.question, original.mood);
    }

    // ──────────────── 同步对话 ────────────────

    /**
     * 发送情绪内容，构建多轮上下文，调用 AI 并保存
     */
    public TreeHoleMessage askAndSave(Long userId, String question, String mood) {
        if (!rateLimitService.isAllowed(userId)) {
            long retry = rateLimitService.getRemainingSeconds(userId);
            throw new com.example.chat.exception.ChatServiceException("treehole", "RATE_LIMITED",
                    "发送太频繁，请 " + retry + " 秒后再试");
        }

        // LangChain4j 模式
        if (langChain4jEnabled && langChain4jTreeHoleService != null) {
            try {
                String answer = langChain4jTreeHoleService.chat(userId, question);
                TreeHoleMessage m = new TreeHoleMessage();
                m.reqId = UUID.randomUUID().toString();
                m.userId = userId;
                m.question = question;
                m.mood = mood;
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
                m.status = "done";
                m.provider = "langchain4j";
                m.model = "qwen-plus";
                m.tokens = Math.max(1, answer.length() / 2);
                treeHoleRepository.insert(m);
                historyBuilder.saveMemoryIfAvailable(userId, question, m.answerJson);
                log.info("[LangChain4j] 树洞对话完成 user={} answerLen={}", userId, answer.length());
                return m;
            } catch (Exception e) {
                log.warn("[LangChain4j] 树洞调用失败，降级到原有模式: {}", e.getMessage());
            }
        }

        // 构建历史上下文
        TreeHoleHistoryBuilder.HistoryContext ctx = historyBuilder.build(userId, question);
        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(ctx.systemPrompt()
                + "\n\n如果问题复杂需要分析，先把分析推理写在 <thinking>...</thinking> 标签中，再给出最终回应。简单问题直接回应。"));
        messages.addAll(ctx.messages());

        String fullQuestion = (mood != null && !mood.isBlank())
                ? "[情绪：" + mood + "] " + question : question;
        messages.add(LLMMessage.user(fullQuestion));

        TreeHoleMessage m = new TreeHoleMessage();
        m.reqId = UUID.randomUUID().toString();
        m.userId = userId;
        m.question = question;
        m.mood = mood;
        m.status = "pending";
        treeHoleRepository.insert(m);

        ModelConfig config = modelConfigResolver.resolveMainModel();

        try {
            String answer;
            if (ragService != null && treeholeKbId > 0) {
                answer = ragService.invokeWithRAG(config, treeholeKbId, question, messages,
                        0.85, "treehole", llmConfig.getBaseUrl(), llmConfig.getApiKey());
            } else {
                answer = llmInvoker.invoke(config, messages, 0.85, "treehole", llmConfig.getBaseUrl(), llmConfig.getApiKey());
            }
            m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            m.status = "done";
            m.provider = config.provider;
            m.model = config.model;
            m.tokens = Math.max(1, (answer != null ? answer.length() : 0) / 2);
        } catch (Exception e) {
            log.error("TreeHole AI 调用失败", e);
            m.answerJson = "{\"answer\":\"树洞暂时出了点小问题，请稍后再试...\"}";
            m.status = "error";
        }

        treeHoleRepository.updateByReqId(m);

        if ("done".equals(m.status)) {
            historyBuilder.saveMemoryIfAvailable(userId, question, m.answerJson);
        }
        return m;
    }
}
