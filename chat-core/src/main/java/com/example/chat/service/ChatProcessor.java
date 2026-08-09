package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

@Service
public class ChatProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatProcessor.class);
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService modelExecutor;
    private final BroadcastService broadcastService;
    private final LLMCallRecorder llmCallRecorder;
    private final LLMInvoker llmInvoker;

    /** 对话记忆服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.rag.service.ConversationMemoryService memoryService;

    /** LangChain4j 个人对话服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.langchain4j.LangChain4jPersonalChatService langChain4jPersonalChatService;

    /** 是否启用 LangChain4j 个人对话模式 */
    @org.springframework.beans.factory.annotation.Value("${app.langchain4j.personal.enabled:false}")
    private boolean langChain4jPersonalEnabled;

    /** 对话摘要服务（可选注入，失败不阻塞主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SummaryService summaryService;

    /** 知识图谱服务（可选注入，失败不阻塞主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

    /** 历史对话摘要服务（可选注入，压缩过长历史避免上下文溢出） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private HistorySummaryService historySummaryService;

    /** 工具调度器（可选注入，仅在 app.agent.enabled=true 时存在） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.agent.tool.ToolDispatcher toolDispatcher;

    @org.springframework.beans.factory.annotation.Value("${app.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @org.springframework.beans.factory.annotation.Value("${app.llm.model:qwen-plus}")
    private String defaultModel;

    @org.springframework.beans.factory.annotation.Value("${app.llm.provider:qwen}")
    private String defaultProvider;

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /** 流式生成停止标记：reqId -> 是否请求停止 */
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    /** 请求停止某个流式生成 */
    public void requestStop(String reqId) {
        stopFlags.put(reqId, new AtomicBoolean(true));
    }

    /** 判断某个 reqId 是否已请求停止 */
    public boolean isStopped(String reqId) {
        AtomicBoolean flag = stopFlags.get(reqId);
        return flag != null && flag.get();
    }

    public ChatProcessor(MessageRepository messageRepository,
                         ModelConfigRepository modelConfigRepository,
                         RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper,
                         BroadcastService broadcastService,
                         LLMCallRecorder llmCallRecorder,
                         LLMInvoker llmInvoker) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.llmCallRecorder = llmCallRecorder;
        this.llmInvoker = llmInvoker;
        this.modelExecutor = new ThreadPoolExecutor(
                5,
                20,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "llm-worker-" + System.currentTimeMillis());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
    }

    public void process(Map<String, Object> payload) {
        long startTime = System.currentTimeMillis();
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        boolean isPrivate = "true".equals(String.valueOf(payload.get("private")));

        try {
            List<ModelConfig> allConfigs = modelConfigRepository.findAllEnabled().stream()
                    .filter(c -> c.modelType == null || "chat".equalsIgnoreCase(c.modelType))
                    .sorted(Comparator.comparingInt(config -> config.priority != null ? config.priority : 100))
                    .toList();

            if (allConfigs.isEmpty()) {
                ModelConfig fallback = new ModelConfig();
                fallback.provider = defaultProvider;
                fallback.model = defaultModel;
                fallback.apiKeyEncrypted = defaultApiKey;
                fallback.priority = 100;
                fallback.enabled = true;
                allConfigs = List.of(fallback);
            }

            if (isPrivate) {
                String switchResult = trySwitchModel(userId, question, allConfigs);
                if (switchResult != null) {
                    String displayText = switchResult;
                    try {
                        Map<?, ?> parsed = objectMapper.readValue(switchResult, Map.class);
                        displayText = (String) parsed.get("answer");
                    } catch (Exception ignored) {}
                    Message m = messageRepository.findByReqId(reqId);
                    if (m != null) {
                        m.answerJson = switchResult;
                        m.status = "done";
                        messageRepository.updateByReqId(m);
                    }
                    broadcastService.broadcast("/topic/user." + userId,
                            WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", displayText).toMap());
                    return;
                }
            }

            String cached = null;
            try {
                cached = redisTemplate.opsForValue().get(buildCacheKey(question));
            } catch (Exception ex) {
                log.warn("[WARN] Redis read failed, skipping cache: {}", ex.getMessage());
            }

            if (cached != null) {
                broadcastService.broadcast("/topic/user." + userId,
                        WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", cached).toMap());
                Message m = messageRepository.findByReqId(reqId);
                if (m != null) {
                    try {
                        m.answerJson = objectMapper.writeValueAsString(Map.of("answer", cached));
                    } catch (Exception e) {
                        m.answerJson = "{\"answer\":\"\"}";
                    }
                    m.status = "done";
                    messageRepository.updateByReqId(m);
                }
                return;
            }

            // LangChain4j 模式（个人对话空间）：AiServices 自动编排记忆+工具
            if (isPrivate && langChain4jPersonalEnabled && langChain4jPersonalChatService != null) {
                try {
                    String answer = langChain4jPersonalChatService.chat(userId, question);
                    // 保存消息
                    Message m = new Message();
                    m.reqId = reqId;
                    m.userId = userId;
                    m.question = question;
                    m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
                    m.status = "done";
                    m.provider = "langchain4j";
                    m.model = "qwen-plus";
                    m.tokens = Math.max(1, answer.length() / 2);
                    m.isPrivate = 1;
                    messageRepository.insert(m);

                    // 推送给前端
                    completeWithAnswer(reqId, userId, question, answer, "langchain4j", "qwen-plus", System.currentTimeMillis());
                    return;
                } catch (Exception e) {
                    log.warn("[LangChain4j] 个人对话调用失败，降级到原有模式: {}", e.getMessage());
                }
            }

            List<ModelConfig> configs;
            Long boundModelId = getPersonalModelId(userId);
            if (isPrivate && boundModelId == null) {
                List<ModelConfig> deepseekConfigs = allConfigs.stream()
                        .filter(c -> "deepseek".equalsIgnoreCase(c.provider))
                        .toList();
                configs = deepseekConfigs.isEmpty() ? allConfigs : deepseekConfigs;
            } else if (boundModelId != null) {
                configs = allConfigs.stream()
                        .filter(c -> c.id != null && c.id.equals(boundModelId))
                        .toList();
                if (configs.isEmpty()) {
                    configs = allConfigs.stream()
                            .filter(c -> "deepseek".equalsIgnoreCase(c.provider))
                            .toList();
                    configs = configs.isEmpty() ? allConfigs : configs;
                    log.warn("[WARN] 用户 {} 绑定的模型ID={} 不在可用chat模型中，回退到 {}", userId, boundModelId,
                            configs.isEmpty() ? "无可用模型" : configs.get(0).provider);
                }
            } else {
                String bestProvider = selectBestProvider(question);
                List<ModelConfig> preferred = allConfigs.stream()
                        .filter(c -> bestProvider.equalsIgnoreCase(c.provider))
                        .toList();
                configs = preferred.isEmpty() ? allConfigs : preferred;
                log.info("[INFO] 群聊智能路由: question='{}' -> provider={}",
                        question.length() > 30 ? question.substring(0, 30) + "..." : question,
                        configs.get(0).provider);
            }

            if (configs.isEmpty()) {
                log.error("[ERROR] 用户 {} 没有可用的 chat 模型", userId);
                broadcastService.broadcast("/topic/user." + userId,
                        WsMessage.error("没有可用的对话模型").withReqId(reqId).toMap());
                return;
            }

            final List<LLMMessage> historyMessages;
            if (isPrivate) {
                historyMessages = buildHistoryMessages(userId, question);
            } else {
                // 群聊也构建带记忆的上下文
                historyMessages = buildGroupChatMessages(userId, question);
            }

            // 个人对话空间：走流式输出
            if (isPrivate && historyMessages != null) {
                final ModelConfig fConfig = configs.get(0);
                final List<LLMMessage> fHistory = historyMessages;
                final String fReqId = reqId;
                final Long fUserId = userId;
                final String fQuestion = question;

                final long fStartTime = System.currentTimeMillis();
                CompletableFuture.runAsync(() -> {
                    try {
                        broadcastService.broadcast("/topic/user." + fUserId,
                                WsMessage.of(WsMessage.TYPE_STREAM_START).withReqId(fReqId).with("model", fConfig.model).toMap());

                        // 先尝试工具调度：如果命中工具，用工具增强后的回答（非流式一次性推送）
                        if (toolDispatcher != null) {
                            try {
                                String toolAnswer = toolDispatcher.dispatch(fQuestion, fConfig, fHistory,
                                        0.7, "personal", defaultBaseUrl, defaultApiKey);
                                if (toolAnswer != null && !toolAnswer.isBlank()) {
                                    broadcastService.broadcast("/topic/user." + fUserId,
                                            WsMessage.streamToken(toolAnswer).withReqId(fReqId).toMap());
                                    if (isStopped(fReqId)) {
                                        broadcastService.broadcast("/topic/user." + fUserId,
                                                WsMessage.stopped(toolAnswer).withReqId(fReqId).toMap());
                                        Message stoppedMsg = messageRepository.findByReqId(fReqId);
                                        if (stoppedMsg != null) {
                                            try {
                                                stoppedMsg.answerJson = objectMapper.writeValueAsString(Map.of("answer", toolAnswer));
                                            } catch (Exception e) {
                                                stoppedMsg.answerJson = "{\"answer\":\"\"}";
                                            }
                                            stoppedMsg.status = "stopped";
                                            messageRepository.updateByReqId(stoppedMsg);
                                        }
                                    } else {
                                        completeWithAnswer(fReqId, fUserId, fQuestion, toolAnswer, fConfig.provider, fConfig.model, fStartTime);
                                    }
                                    return;
                                }
                            } catch (Exception toolEx) {
                                log.warn("[ToolDispatcher] 工具调度失败，回退到普通流式: {}", toolEx.getMessage());
                            }
                        }

                        String fullAnswer = llmInvoker.invokeStream(fConfig, fHistory, 0.7, "personal",
                                defaultBaseUrl, defaultApiKey,
                                token -> {
                                    // 检查停止标记：已停止则不再推送 token
                                    if (stopFlags.getOrDefault(fReqId, new AtomicBoolean(false)).get()) {
                                        return;
                                    }
                                    broadcastService.broadcast("/topic/user." + fUserId,
                                            WsMessage.streamToken(token).withReqId(fReqId).toMap());
                                });

                        if (isStopped(fReqId)) {
                            // 已停止：推送 stopped 消息，不更新 answer（保留前端已渲染的内容）
                            broadcastService.broadcast("/topic/user." + fUserId,
                                    WsMessage.stopped(fullAnswer).withReqId(fReqId).toMap());
                            // 更新消息状态为 stopped
                            Message stoppedMsg = messageRepository.findByReqId(fReqId);
                            if (stoppedMsg != null) {
                                try {
                                    stoppedMsg.answerJson = objectMapper.writeValueAsString(Map.of("answer", fullAnswer));
                                } catch (Exception e) {
                                    stoppedMsg.answerJson = "{\"answer\":\"\"}";
                                }
                                stoppedMsg.status = "stopped";
                                messageRepository.updateByReqId(stoppedMsg);
                            }
                        } else {
                            completeWithAnswer(fReqId, fUserId, fQuestion, fullAnswer, fConfig.provider, fConfig.model, fStartTime);
                        }
                    } catch (Exception ex) {
                        log.error("[ERROR] 流式调用失败: {}", ex.getMessage(), ex);
                        broadcastService.broadcast("/topic/user." + fUserId,
                                WsMessage.error("生成失败: " + ex.getMessage()).withReqId(fReqId).toMap());
                    } finally {
                        // 清理停止标记
                        stopFlags.remove(fReqId);
                    }
                }, modelExecutor);
                return;
            }

            // 群聊：并发调用
            List<CompletableFuture<LLMResult>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicInteger finishedCount = new AtomicInteger(0);
            int totalModels = configs.size();

            for (ModelConfig config : configs) {
                CompletableFuture<LLMResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        String answer;
                        if (historyMessages != null) {
                            answer = llmInvoker.invoke(config, historyMessages, 0.7, "chat",
                                    defaultBaseUrl, defaultApiKey);
                        } else {
                            answer = llmInvoker.invoke(config, question, 0.7, "chat",
                                    defaultBaseUrl, defaultApiKey);
                        }
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, modelExecutor);

                future.handle((result, ex) -> {
                    int finished = finishedCount.incrementAndGet();

                    if (ex != null) {
                        log.error("[ERROR] 模型 {} 调用失败: {}", config.model,
                                ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                        llmCallRecorder.record(config.provider, config.model, "chat", false,
                                System.currentTimeMillis() - startTime, 0);
                    } else {
                        log.info("[INFO] 模型 {} 返回成功, answer长度={}", config.model,
                                result.answer != null ? result.answer.length() : 0);

                        if (completed.compareAndSet(false, true)) {
                            log.info("[INFO] 抢到首发, reqId={}", reqId);
                            try {
                                if (isPrivate) {
                                    savePersonalModelId(userId, result.config.id);
                                }
                                completeWithAnswer(reqId, userId, question, result.answer, result.config.provider, result.config.model, startTime);
                                // 群聊额外保存记忆（completeWithAnswer 内默认存 personal）
                                if (!isPrivate && memoryService != null) {
                                    try {
                                        memoryService.saveConversation("chat", userId, question, result.answer);
                                    } catch (Exception memEx) {
                                        log.warn("[Memory] 群聊记忆保存失败 user={} error={}", userId, memEx.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                log.error("[ERROR] completeWithAnswer 失败: {}", e.getMessage(), e);
                            }
                        }
                    }

                    if (finished == totalModels && !completed.get()) {
                        log.warn("[WARN] 所有 {} 个模型都失败了, reqId={}", totalModels, reqId);
                        broadcastService.broadcast("/topic/user." + userId,
                                WsMessage.error("所有模型调用均失败").withReqId(reqId).toMap());
                    }

                    return null;
                });
                futures.add(future);
            }

        } catch (Exception ex) {
            log.error("[ERROR] ChatProcessor: {}", ex.getMessage(), ex);
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error(ex.getMessage()).withReqId(reqId).toMap());
        }
    }

    /**
     * 重新生成：根据原始 reqId 找到原始 question，重新走流式处理流程
     * 生成新的 reqId，复用 process() 的流式推送逻辑
     */
    public void regenerate(String oldReqId, Long userId) {
        Message original = messageRepository.findByReqId(oldReqId);
        if (original == null) {
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("原始消息不存在，无法重新生成").withReqId(oldReqId).toMap());
            return;
        }
        if (original.question == null || original.question.isBlank()) {
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("原始问题为空，无法重新生成").withReqId(oldReqId).toMap());
            return;
        }

        String newReqId = "regen-" + java.util.UUID.randomUUID().toString();
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("req_id", newReqId);
        payload.put("user_id", userId);
        payload.put("question", original.question);
        payload.put("private", "true");
        payload.put("ai_answer", "true");

        // 先插入新的消息记录
        Message m = new Message();
        m.reqId = newReqId;
        m.userId = userId;
        m.question = original.question;
        m.status = "queued";
        m.isPrivate = 1;
        try {
            messageRepository.insert(m);
        } catch (Exception ex) {
            log.warn("[WARN] regenerate 插入消息记录失败: {}", ex.getMessage());
        }

        log.info("[INFO] regenerate oldReqId={} newReqId={} userId={}", oldReqId, newReqId, userId);
        process(payload);
    }

    public void processWithFile(String reqId, Long userId, String question, String fileName, byte[] fileContent, String mimeType) {
        try {
            log.info("[INFO] processWithFile: reqId={}, fileName={}, mimeType={}", reqId, fileName, mimeType);
            final String lowerName = fileName != null ? fileName.toLowerCase() : "";
            final boolean isImage = mimeType != null && mimeType.startsWith("image/");
            final boolean isExcel = lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls");
            final boolean isPpt = lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt");

            final String fileTextContent;
            final String fileBase64;

            if (isImage) {
                fileBase64 = Base64.getEncoder().encodeToString(fileContent);
                fileTextContent = null;
            } else if (isExcel) {
                fileBase64 = null;
                String extracted = extractExcelContent(fileContent);
                if (extracted.length() > 30000) extracted = extracted.substring(0, 30000) + "\n...[已截断]";
                fileTextContent = extracted;
            } else if (isPpt) {
                fileBase64 = null;
                String extracted = extractPptContent(fileContent);
                if (extracted.length() > 30000) extracted = extracted.substring(0, 30000) + "\n...[已截断]";
                fileTextContent = extracted;
            } else {
                fileBase64 = null;
                String raw = new String(fileContent, StandardCharsets.UTF_8);
                if (raw.length() > 30000) raw = raw.substring(0, 30000) + "\n...[已截断]";
                fileTextContent = raw;
            }

            List<ModelConfig> allConfigs = modelConfigRepository.findAllEnabled().stream()
                    .sorted(Comparator.comparingInt(config -> config.priority != null ? config.priority : 100))
                    .toList();

            if (allConfigs.isEmpty()) {
                ModelConfig fallback = new ModelConfig();
                fallback.provider = defaultProvider;
                fallback.model = defaultModel;
                fallback.apiKeyEncrypted = defaultApiKey;
                fallback.priority = 100;
                fallback.enabled = true;
                allConfigs = List.of(fallback);
            }

            List<ModelConfig> configs;
            if (isImage) {
                List<ModelConfig> imageParse = allConfigs.stream()
                        .filter(c -> c.id != null && c.id == 8L && "image_parse".equals(c.modelType))
                        .toList();
                if (imageParse.isEmpty()) {
                    imageParse = allConfigs.stream()
                            .filter(c -> "image_parse".equals(c.modelType))
                            .toList();
                }
                configs = imageParse.isEmpty() ? allConfigs : imageParse;
            } else {
                List<ModelConfig> textParse = allConfigs.stream()
                        .filter(c -> c.id != null && c.id == 9L && "text_parse".equals(c.modelType))
                        .toList();
                if (textParse.isEmpty()) {
                    textParse = allConfigs.stream()
                            .filter(c -> "text_parse".equals(c.modelType) && Boolean.TRUE.equals(c.enabled))
                            .toList();
                }
                if (!textParse.isEmpty()) {
                    configs = textParse;
                } else {
                    Long boundModelId = getPersonalModelId(userId);
                    if (boundModelId != null) {
                        List<ModelConfig> bound = allConfigs.stream()
                                .filter(c -> c.id != null && c.id.equals(boundModelId))
                                .toList();
                        configs = bound.isEmpty() ? allConfigs : bound;
                    } else {
                        List<ModelConfig> textConfigs = allConfigs.stream()
                                .filter(c -> "qwen".equalsIgnoreCase(c.provider))
                                .toList();
                        configs = textConfigs.isEmpty() ? allConfigs : textConfigs;
                    }
                }
            }

            final List<LLMMessage> fileHistoryMessages = buildFileHistoryMessages(userId, question, fileName, fileTextContent, isImage, fileBase64, mimeType);

            List<CompletableFuture<?>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            for (ModelConfig config : configs) {
                CompletableFuture<?> fullFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        String answer = llmInvoker.invoke(config, fileHistoryMessages, 0.7, "personal",
                                defaultBaseUrl, defaultApiKey);
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        log.error("[ERROR] processWithFile 模型调用失败 [{}/{}]: {}", config.provider, config.model, ex.getMessage());
                        return null;
                    }
                }, modelExecutor).thenAccept(result -> {
                    if (result != null && completed.compareAndSet(false, true)) {
                        savePersonalModelId(userId, result.config.id);
                        completeWithAnswer(reqId, userId, question, result.answer, result.config.provider, result.config.model, System.currentTimeMillis());
                    }
                });

                futures.add(fullFuture);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((unused, ex) -> {
                if (!completed.get()) {
                    broadcastService.broadcast("/topic/user." + userId,
                            WsMessage.error("所有模型调用均失败").withReqId(reqId).toMap());
                }
            });

        } catch (Exception ex) {
            log.error("[ERROR] ChatProcessor processWithFile: {}", ex.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error(ex.getMessage()).withReqId(reqId).toMap());
        }
    }

    private List<LLMMessage> buildHistoryMessages(Long userId, String currentQuestion) {
        List<Message> recent = getRecentMessages(userId);
        List<LLMMessage> historyMsgs = new ArrayList<>();

        if (recent != null) {
            for (Message m : recent) {
                historyMsgs.add(LLMMessage.user(m.question));
                historyMsgs.add(LLMMessage.assistant(extractAnswerText(m.answerJson)));
            }
        }

        // 系统 prompt（含记忆上下文）
        StringBuilder systemPrompt = new StringBuilder("你是用户的AI助手，请友好地回答问题。");
        if (memoryService != null) {
            String memory = memoryService.buildMemoryContext("personal", userId, currentQuestion);
            if (memory != null && !memory.isBlank()) {
                systemPrompt.append("\n\n").append(memory);
            }
        }

        // 历史压缩：过长时用摘要替代早期消息
        historyMsgs = compressHistory("personal", userId, historyMsgs, systemPrompt);

        List<LLMMessage> messages = new ArrayList<>();
        messages.add(LLMMessage.system(systemPrompt.toString()));
        messages.addAll(historyMsgs);
        messages.add(LLMMessage.user(currentQuestion));
        return messages;
    }

    /** 历史消息压缩包装方法 */
    private List<LLMMessage> compressHistory(String scene, Long userId,
                                                       List<LLMMessage> historyMsgs,
                                                       StringBuilder systemPrompt) {
        if (historySummaryService != null && !historyMsgs.isEmpty()) {
            try {
                return historySummaryService.compress(scene, userId, historyMsgs, systemPrompt);
            } catch (Exception e) {
                log.warn("[HistorySummary] compress failed, fallback to full history: {}", e.getMessage());
            }
        }
        return historyMsgs;
    }

    private List<Message> getRecentMessages(Long userId) {
        try {
            List<Message> recent = messageRepository.findRecentByUserId(userId);
            if (recent != null && !recent.isEmpty()) {
                return recent;
            }
        } catch (Exception ex) {
            log.warn("[WARN] Failed to load recent messages: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * 构建群聊消息（带记忆上下文，但不带历史对话——群聊历史由前端展示）
     */
    private List<LLMMessage> buildGroupChatMessages(Long userId, String currentQuestion) {
        List<LLMMessage> messages = new ArrayList<>();

        // 系统 prompt（含记忆上下文）
        StringBuilder systemPrompt = new StringBuilder("你是AI伙伴群聊中的AI角色，请友好、有趣地回答问题。");
        if (memoryService != null) {
            String memory = memoryService.buildMemoryContext("chat", userId, currentQuestion);
            if (memory != null && !memory.isBlank()) {
                systemPrompt.append("\n\n").append(memory);
            }
        }
        messages.add(LLMMessage.system(systemPrompt.toString()));
        messages.add(LLMMessage.user(currentQuestion));
        return messages;
    }

    private List<LLMMessage> buildFileHistoryMessages(Long userId, String question, String fileName,
                                                                String fileTextContent, boolean isImage,
                                                                String fileBase64, String mimeType) {
        List<Message> recent = getRecentMessages(userId);
        List<LLMMessage> historyMsgs = new ArrayList<>();

        if (recent != null) {
            for (Message m : recent) {
                historyMsgs.add(LLMMessage.user(m.question));
                historyMsgs.add(LLMMessage.assistant(extractAnswerText(m.answerJson)));
            }
        }

        // 文件场景的 system prompt
        StringBuilder systemPrompt = new StringBuilder("请根据提供的文件内容和对话历史，友好地回答用户问题。");
        historyMsgs = compressHistory("file", userId, historyMsgs, systemPrompt);

        List<LLMMessage> messages = new ArrayList<>();
        // 仅当有摘要时才加入 system 消息
        if (systemPrompt.indexOf("历史对话摘要") >= 0) {
            messages.add(LLMMessage.system(systemPrompt.toString()));
        }
        messages.addAll(historyMsgs);

        if (isImage) {
            String imageQuestion = (question == null || question.isBlank()) ? "请描述这张图片" : question;
            messages.add(LLMMessage.userWithImage(imageQuestion, fileBase64, mimeType));
        } else {
            String content = question + "\n\n--- 以下是文件 [" + fileName + "] 的内容 ---\n" + fileTextContent + "\n--- 文件内容结束 ---";
            messages.add(LLMMessage.user(content));
        }

        return messages;
    }

    private String extractExcelContent(byte[] fileContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(fileContent))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    XSSFRow row = sheet.getRow(r);
                    if (row == null) { sb.append("\n"); continue; }
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        XSSFCell cell = row.getCell(c);
                        cells.add(cell != null ? cell.toString().trim() : "");
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从 PPT 文件二进制内容中提取文本（按幻灯片 → 文本形状遍历）。
     *
     * @param fileContent PPT 文件二进制内容
     * @return 提取的文本（每页以 === Slide N === 分隔）
     */
    private String extractPptContent(byte[] fileContent) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (XMLSlideShow ppt = new XMLSlideShow(new java.io.ByteArrayInputStream(fileContent))) {
            List<XSLFSlide> slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                XSLFSlide slide = slides.get(i);
                sb.append("=== Slide ").append(i + 1).append(" ===\n");
                for (var shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText().trim();
                        if (!text.isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void completeWithAnswer(String reqId, Long userId, String question, String answer, String provider, String model, long startTime) {
        long latency = System.currentTimeMillis() - startTime;
        int answerLen = answer != null ? answer.length() : 0;
        // token 估算：中文约 1.5 字/token，英文约 4 字符/token，取折中 2 字符/token
        int estimatedTokens = Math.max(1, answerLen / 2);
        llmCallRecorder.record(provider, model, "chat", true, latency, answerLen);
        log.info("[STATS] provider={} model={} latency={}ms answerLen={} tokens~{}", provider, model, latency, answerLen, estimatedTokens);

        broadcastService.broadcast("/topic/user." + userId,
                WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId)
                        .with("answer", answer).with("latency", latency)
                        .with("tokens", estimatedTokens).with("model", model).toMap());

        broadcastService.broadcast("/topic/public-questions",
                WsMessage.of(WsMessage.TYPE_ANSWER).withReqId(reqId)
                        .with("user_id", userId).with("answer", answer).toMap());

        String cacheKey = buildCacheKey(question, provider, model);
        try {
            redisTemplate.opsForValue().set(cacheKey, answer, CACHE_TTL);
        } catch (Exception ex) {
            log.warn("[WARN] Redis write failed: {}", ex.getMessage());
        }

        // 保存对话记忆
        if (memoryService != null) {
            try {
                memoryService.saveConversation("personal", userId, question, answer);
            } catch (Exception ex) {
                log.warn("[Memory] 个人对话记忆保存失败 user={} error={}", userId, ex.getMessage());
            }
        }

        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            } catch (Exception e) {
                m.answerJson = "{\"answer\":\"\"}";
            }
            m.status = "done";
            m.provider = provider;
            m.model = model;
            m.tokens = estimatedTokens;
            messageRepository.updateByReqId(m);

            // 触发对话摘要生成（异步，失败不阻塞主流程）
            if (summaryService != null && m.id != null) {
                try {
                    summaryService.summarizeAsync(m.id, question, answer);
                } catch (Exception ex) {
                    log.warn("[Summary] 触发摘要生成失败 messageId={}: {}", m.id, ex.getMessage());
                }
            }

            // 触发知识图谱抽取（异步，失败不阻塞主流程）
            if (knowledgeGraphService != null && m.id != null) {
                try {
                    knowledgeGraphService.extractAndSaveAsync(m.id, question, answer, "chat");
                } catch (Exception ex) {
                    log.warn("[KnowledgeGraph] 触发知识抽取失败 messageId={}: {}", m.id, ex.getMessage());
                }
            }
        }
    }

    /**
     * 根据问题特征智能选择最优模型 provider
     * - DeepSeek: 代码/算法/逻辑推理/数学/技术问题
     * - 千问: 创意写作/诗歌/故事/角色扮演
     * - 豆包: 日常对话/闲聊/通用问题（默认）
     */
    private String selectBestProvider(String question) {
        if (question == null || question.isBlank()) return "doubao";
        String q = question.toLowerCase();

        String[] deepSeekKeywords = {"代码", "编程", "算法", "bug", "error", "java", "python", "javascript",
                "sql", "逻辑", "推理", "数学", "计算", "技术", "架构", "接口", "函数", "正则", "复杂"};
        for (String kw : deepSeekKeywords) {
            if (q.contains(kw)) return "deepseek";
        }

        String[] qwenKeywords = {"写诗", "写一篇", "创作", "故事", "小说", "诗歌", "散文", "作文",
                "角色扮演", "续写", "创意", "文案", "广告语", "标语", "对联"};
        for (String kw : qwenKeywords) {
            if (q.contains(kw)) return "qwen";
        }

        return "doubao";
    }

    /**
     * 判断指定 provider 是否支持视觉（图片）输入。
     *
     * @param provider 模型 provider 名称
     * @return true 表示支持图片输入
     */
    private boolean isVisionSupported(String provider) {
        if (provider == null) return false;
        switch (provider.toLowerCase()) {
            case "qwen":
            case "doubao":
            case "openai":
            case "azure":
                return true;
            default:
                return false;
        }
    }

    /**
     * 判断指定模型配置是否为视觉模型（image_parse 类型或 provider 支持视觉）。
     *
     * @param config 模型配置
     * @return true 表示该模型可处理图片
     */
    private boolean isVisionModel(ModelConfig config) {
        if ("image_parse".equals(config.modelType)) return true;
        return isVisionSupported(config.provider);
    }

    /**
     * 计算输入字符串的 SHA-256 哈希值（16 进制字符串）；计算失败时回退到 hashCode。
     *
     * @param input 输入字符串
     * @return 哈希值
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /**
     * 构建问题级缓存 key（不区分 provider/model，命中即所有模型共用）。
     *
     * @param question 问题文本
     * @return Redis 缓存 key
     */
    private String buildCacheKey(String question) {
        return "question:" + sha256(question + "::model-pool");
    }

    /**
     * 构建问题+模型级缓存 key（区分 provider/model）。
     *
     * @param question 问题文本
     * @param provider 模型 provider
     * @param model    模型名称
     * @return Redis 缓存 key
     */
    private String buildCacheKey(String question, String provider, String model) {
        return "question:" + sha256(question + "::" + (provider == null ? "" : provider) + "::" + (model == null ? "" : model));
    }

    /**
     * 从 Redis 读取用户绑定的个人模型 ID；读取失败或未绑定时返回 null。
     *
     * @param userId 用户 ID
     * @return 绑定的模型 ID，或 null
     */
    private Long getPersonalModelId(Long userId) {
        try {
            String val = redisTemplate.opsForValue().get("personal_model:" + userId);
            if (val != null && !val.isBlank()) {
                return Long.parseLong(val);
            }
        } catch (Exception ex) {
            log.warn("[WARN] Redis read personal_model failed: {}", ex.getMessage());
        }
        return null;
    }

    /**
     * 将用户绑定的个人模型 ID 保存到 Redis（TTL 365 天）。
     *
     * @param userId  用户 ID
     * @param modelId 模型 ID
     */
    private void savePersonalModelId(Long userId, Long modelId) {
        try {
            redisTemplate.opsForValue().set("personal_model:" + userId, String.valueOf(modelId), Duration.ofDays(365));
        } catch (Exception ex) {
            log.warn("[WARN] Redis write personal_model failed: {}", ex.getMessage());
        }
    }

    /**
     * 尝试根据用户输入的切换指令（如"切换到千问"）切换个人模型。
     * <p>命中切换指令时：保存绑定关系并返回切换成功提示的 JSON；未命中返回 null。</p>
     *
     * @param userId     用户 ID
     * @param question   用户问题（可能包含切换指令）
     * @param allConfigs 全部可用模型配置
     * @return 切换成功提示 JSON，或 null（未命中切换指令）
     */
    private String trySwitchModel(Long userId, String question, List<ModelConfig> allConfigs) {
        String q = question.trim().toLowerCase();
        if (!q.contains("切换") && !q.contains("换") && !q.contains("改用")) {
            return null;
        }

        ModelConfig target = null;
        for (ModelConfig config : allConfigs) {
            String provider = config.provider != null ? config.provider.toLowerCase() : "";
            switch (provider) {
                case "doubao":
                    if (q.contains("豆包") || q.contains("doubao")) target = config;
                    break;
                case "qwen":
                    if (q.contains("千问") || q.contains("qwen") || q.contains("通义")) target = config;
                    break;
                case "deepseek":
                    if (q.contains("deepseek") || q.contains("深度求索")) target = config;
                    break;
                case "zhipu":
                    if (q.contains("智谱") || q.contains("zhipu") || q.contains("glm")) target = config;
                    break;
            }
            if (target != null) break;
        }

        if (target != null) {
            savePersonalModelId(userId, target.id);
            String displayName;
            switch (target.provider.toLowerCase()) {
                case "doubao": displayName = "豆包"; break;
                case "qwen": displayName = "千问"; break;
                case "deepseek": displayName = "DeepSeek"; break;
                case "zhipu": displayName = "智谱 GLM"; break;
                default: displayName = target.provider; break;
            }
            String msg = "✅ 已成功切换为「" + displayName + "」模型，后续所有问题都将由该模型回答。";
            return "{\"answer\":\"" + msg.replace("\"", "\\\"") + "\"}";
        }

        return null;
    }

    /**
     * LLM 调用结果封装（包含模型配置和生成的答案）。
     */
    private static class LLMResult {
        private final ModelConfig config;
        private final String answer;

        /**
         * 构造 LLM 调用结果。
         *
         * @param config 调用使用的模型配置
         * @param answer LLM 生成的答案
         */
        private LLMResult(ModelConfig config, String answer) {
            this.config = config;
            this.answer = answer;
        }
    }

    /** 从 answerJson 中提取纯文本回答（避免把 JSON 格式传给 LLM 导致模仿） */
    private String extractAnswerText(String answerJson) {
        if (answerJson == null || answerJson.isBlank()) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = new com.fasterxml.jackson.databind.ObjectMapper().readValue(answerJson, Map.class);
            Object answer = m.get("answer");
            return answer != null ? answer.toString() : answerJson;
        } catch (Exception e) {
            return answerJson;
        }
    }
}
