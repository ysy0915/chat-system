package com.example.chat.service;

import com.example.chat.client.RagClient;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.intent.IntentResult;
import com.example.chat.intent.IntentRoutingHelper;
import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.funnel.IntentFunnelEngine;
import com.example.chat.intent.funnel.ThinkingStreamParser;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.DataAccessException;

@Service
public class ChatProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatProcessor.class);
    private final MessageRepository messageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService modelExecutor;
    private final BroadcastService broadcastService;
    private final LLMCallRecorder llmCallRecorder;
    private final LLMInvoker llmInvoker;
    private final ChatHistoryBuilder chatHistoryBuilder;
    private final ModelRouter modelRouter;
    private final FileContentExtractor fileContentExtractor;
    private final StreamStopManager streamStopManager;
    private final LlmConfigProperties llmConfig;

    /** RAG 客户端（通过 /internal/rag/* 调用 chat-llm 的知识库检索与对话记忆） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.chat.client.RagClient ragClient;

    /** LangChain4j 个人对话服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.langchain4j.LangChain4jPersonalChatService langChain4jPersonalChatService;

    /** 是否启用 LangChain4j 个人对话模式 */
    @org.springframework.beans.factory.annotation.Value("${app.langchain4j.personal.enabled:false}")
    private boolean langChain4jPersonalEnabled;

    /** 对话自动 RAG 增强：知识问答/任务类问题自动检索知识库（RAG 索引增强生成） */
    @org.springframework.beans.factory.annotation.Value("${app.rag.chat.enabled:true}")
    private boolean chatRagEnabled;

    /** 对话自动 RAG 默认检索的知识库 ID（<=0 表示未配置，不增强） */
    @org.springframework.beans.factory.annotation.Value("${app.rag.chat.kb-id:0}")
    private long chatRagKbId;

    /** 对话自动 RAG 检索 topK */
    @org.springframework.beans.factory.annotation.Value("${app.rag.chat.top-k:3}")
    private int chatRagTopK;

    /** 对话自动 RAG 相似度阈值 */
    @org.springframework.beans.factory.annotation.Value("${app.rag.chat.score-threshold:0.3}")
    private float chatRagScoreThreshold;

    /** 对话自动 RAG 参考资料的字符上限 */
    @org.springframework.beans.factory.annotation.Value("${app.rag.chat.max-chars:2000}")
    private int chatRagMaxChars;

    /** 对话摘要服务（可选注入，失败不阻塞主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SummaryService summaryService;

    /** 知识图谱客户端（可选注入，失败不阻塞主流程；运行时已迁至 chat-llm） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.client.GraphClient graphClient;

    /** 工具调度器（可选注入，仅在 app.agent.enabled=true 时存在） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.agent.tool.ToolDispatcher toolDispatcher;

    /** 技能注册中心（可选注入，Step3 技能注入 System Prompt） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.agent.skill.SkillRegistry skillRegistry;

    /** Multi-Agent 并行工作流指挥官（可选注入，app.agent.planner.enabled=true 时存在） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.agent.planner.AgentWorkflowOrchestrator agentWorkflowOrchestrator;

    /** 长期事实记忆召回 topK */
    @org.springframework.beans.factory.annotation.Value("${app.rag.memory.fact-top-k:5}")
    private int factMemoryTopK;

    /** 意图识别 — 三层漏斗引擎 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IntentFunnelEngine funnelEngine;

    /** 意图路由辅助 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IntentRoutingHelper intentRouting;

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /** 实时数据类问题：知识库无此类内容，跳过检索（由工具/模型实时回答） */
    private static final java.util.regex.Pattern[] REALTIME_PATTERNS = {
            java.util.regex.Pattern.compile("(今天|明天|后天|现在|当前|这几天|最近).*(天气|气温|温度|多少度|几度|下雨|下雪|有雨|晴天|阴天|降温|刮风)"),
            java.util.regex.Pattern.compile("(天气|气温|温度|天气预报).*(怎么样|如何|怎样|多少|几度|适合|穿|出门)"),
            java.util.regex.Pattern.compile("(几点|几点了|现在几点|现在时间|今天星期几|星期几|几月几号|今天是)"),
            java.util.regex.Pattern.compile("(今天|今日|最新|热点).*(新闻|时事|头条|快讯)"),
            java.util.regex.Pattern.compile("(股票|股价|行情|汇率|金价|油价|大盘|基金|涨跌|A股|港股)"),
            java.util.regex.Pattern.compile("(比分|比赛结果|赛果|赛况)"),
    };

    /** 个人数据类问题：知识库无个人数据，跳过检索 */
    private static final java.util.regex.Pattern[] PERSONAL_PATTERNS = {
            java.util.regex.Pattern.compile("我的(订单|账户|余额|消息|设置|资料|记录|聊天|历史|收藏|足迹|状态|积分|会员)"),
    };

    /** 流式生成停止管理 */
    public void requestStop(String reqId) {
        streamStopManager.requestStop(reqId);
    }

    public boolean isStopped(String reqId) {
        return streamStopManager.isStopped(reqId);
    }

    public ChatProcessor(MessageRepository messageRepository,
                         RedisTemplate<String, String> redisTemplate,
                         ObjectMapper objectMapper,
                         BroadcastService broadcastService,
                         LLMCallRecorder llmCallRecorder,
                         LLMInvoker llmInvoker,
                         ChatHistoryBuilder chatHistoryBuilder,
                         ModelRouter modelRouter,
                         FileContentExtractor fileContentExtractor,
                         StreamStopManager streamStopManager,
                         LlmConfigProperties llmConfig) {
        this.messageRepository = messageRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.llmCallRecorder = llmCallRecorder;
        this.llmInvoker = llmInvoker;
        this.chatHistoryBuilder = chatHistoryBuilder;
        this.modelRouter = modelRouter;
        this.fileContentExtractor = fileContentExtractor;
        this.streamStopManager = streamStopManager;
        this.llmConfig = llmConfig;
        this.modelExecutor = ThreadPoolFactory.create(5, 20, 100, "llm-worker");
    }

    public void process(Map<String, Object> payload) {
        long startTime = System.currentTimeMillis();
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        boolean isPrivate = "true".equals(String.valueOf(payload.get("private")));

        try {
            List<ModelConfig> allConfigs = modelRouter.loadChatModels(
                    llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getApiKey());

            if (isPrivate) {
                String switchResult = modelRouter.trySwitch(userId, question, allConfigs);
                if (switchResult != null) {
                    applySwitchResult(reqId, userId, switchResult);
                    return;
                }
                handlePersonalChat(reqId, userId, question, allConfigs);
                return;
            }

            handleGroupChat(reqId, userId, question, allConfigs, startTime);

        } catch (Exception ex) {
            log.error("ChatProcessor 失败", ex);
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error(ex.getMessage()).withReqId(reqId).toMap());
        }
    }

    // ───────────── 个人对话空间 ─────────────

    /**
     * 应用模型切换结果，直接推送给前端并持久化。
     */
    private void applySwitchResult(String reqId, Long userId, String switchResult) {
        String displayText = switchResult;
        try {
            Map<?, ?> parsed = objectMapper.readValue(switchResult, Map.class);
            displayText = (String) parsed.get("answer");
        } catch (JsonProcessingException ignored) {
            log.debug("无法解析切换结果JSON: {}", switchResult);
        }
        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            m.answerJson = switchResult;
            m.status = "done";
            messageRepository.updateByReqId(m);
        }
        broadcastService.broadcast("/topic/user." + userId,
                WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", displayText).toMap());
    }

    /**
     * 个人对话空间主线流程：缓存 → LangChain4j → 流式（含工具调度）。
     */
    private void handlePersonalChat(String reqId, Long userId, String question,
                                     List<ModelConfig> allConfigs) {
        if (checkCacheHit(reqId, userId, question)) {
            log.info("[handlePersonalChat] req_id={} userId={} cache hit, skip LLM", reqId, userId);
            return;
        }

        // 意图识别（异步非阻塞，超时自动降级为 UNKNOWN）
        IntentResult intent = recognizeIntent(question, "personal");

        // LangChain4j 模式（AiServices 自动编排记忆+工具）
        if (langChain4jPersonalEnabled && langChain4jPersonalChatService != null) {
            log.info("[handlePersonalChat] req_id={} userId={} try LangChain4j mode", reqId, userId);
            if (tryLangChain4jChat(reqId, userId, question)) return;
        }

        List<ModelConfig> configs = modelRouter.selectForChat(true, userId, question, allConfigs);
        log.info("[handlePersonalChat] req_id={} userId={} totalModels={} selected={} intent={}",
                reqId, userId, allConfigs.size(), configs.size(), intentLabel(intent));
        if (configs.isEmpty()) {
            log.error("用户 {} 没有可用的 chat 模型", userId);
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("没有可用的对话模型").withReqId(reqId).toMap());
            return;
        }

        List<LLMMessage> historyMessages = chatHistoryBuilder.buildPersonal(userId, question);
        log.info("[handlePersonalChat] req_id={} userId={} historySize={} model={} => doPersonalStream",
                reqId, userId, historyMessages.size(), configs.get(0).model);
        doPersonalStream(reqId, userId, question, configs.get(0), historyMessages, intent);
    }

    // ───────────── 群聊 / 非个人场景 ─────────────

    /**
     * 群聊（或非个人空间）主线：模型选择 → 并发调用 → 首发竞速。
     */
    private void handleGroupChat(String reqId, Long userId, String question,
                                  List<ModelConfig> allConfigs, long startTime) {
        // 意图识别（异步非阻塞）
        IntentResult intent = recognizeIntent(question, "group");

        List<ModelConfig> configs = modelRouter.selectForChat(false, userId, question, allConfigs);
        if (configs.isEmpty()) {
            log.error("用户 {} 没有可用的 chat 模型", userId);
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("没有可用的对话模型").withReqId(reqId).toMap());
            return;
        }

        List<LLMMessage> historyMessages = chatHistoryBuilder.buildGroup(userId, question);
        doGroupConcurrent(reqId, userId, question, configs, historyMessages, startTime, intent);
    }

    // ───────────── 子流程 ─────────────

    private boolean checkCacheHit(String reqId, Long userId, String question) {
        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(buildCacheKey(question));
        } catch (DataAccessException ex) {
            log.warn("Redis read failed, skipping cache: {}", ex.getMessage());
        }
        if (cached == null) return false;

        broadcastService.broadcast("/topic/user." + userId,
                WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", cached).toMap());
        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", cached));
            } catch (JsonProcessingException e) {
                m.answerJson = "{\"answer\":\"\"}";
            }
            m.status = "done";
            messageRepository.updateByReqId(m);
        }
        return true;
    }

    private boolean tryLangChain4jChat(String reqId, Long userId, String question) {
        try {
            String answer = langChain4jPersonalChatService.chat(userId, question);
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
            completeWithAnswer(reqId, userId, question, answer, "langchain4j", "qwen-plus",
                    System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            log.warn("LangChain4j 个人对话调用失败，降级到原有模式: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 个人对话流式输出（含工具调度 + 停止管理 + 意图驱动温度）。
     */
    private void doPersonalStream(String reqId, Long userId, String question,
                                   ModelConfig config, List<LLMMessage> history,
                                   IntentResult intent) {
        long startTime = System.currentTimeMillis();
        double temperature = intent != null ? intentRouting.temperatureFor(intent.category()) : 0.7;
        CompletableFuture.runAsync(() -> {
            log.info("[doPersonalStream] 线程开始 req_id={} userId={} model={} intent={} temp={}",
                    reqId, userId, config.model, intentLabel(intent), temperature);
            try {
                broadcastService.broadcast("/topic/user." + userId,
                        WsMessage.of(WsMessage.TYPE_STREAM_START).withReqId(reqId)
                                .with("model", config.model)
                                .with("intent", intent != null ? intent.category().name() : "UNKNOWN")
                                .toMap());

                // Multi-Agent 并行工作流：超长/跨域任务由 TaskPlanner 拆解为子任务并发执行
                if (agentWorkflowOrchestrator != null) {
                    try {
                        if (agentWorkflowOrchestrator.tryParallelWorkflow(reqId, userId, question,
                                config, temperature)) {
                            log.info("[doPersonalStream] req_id={} 已接管为并行工作流", reqId);
                            return;
                        }
                    } catch (Exception planEx) {
                        log.warn("[doPersonalStream] req_id={} 并行工作流启动失败，降级原流程: {}",
                                reqId, planEx.getMessage());
                    }
                }

                // 工具调度：命中则推送增强后的回答（非流式）
                if (toolDispatcher != null) {
                    String toolAnswer = null;
                    try {
                        toolAnswer = toolDispatcher.dispatch(question, config, history,
                                temperature, "personal", llmConfig.getBaseUrl(), llmConfig.getApiKey());
                    } catch (Exception toolEx) {
                        log.warn("工具调度失败，回退到普通流式: {}", toolEx.getMessage());
                    }
                    if (toolAnswer != null && !toolAnswer.isBlank()) {
                        broadcastService.broadcast("/topic/user." + userId,
                                WsMessage.streamToken(toolAnswer).withReqId(reqId).toMap());
                        if (isStopped(reqId)) {
                            broadcastService.broadcast("/topic/user." + userId,
                                    WsMessage.stopped(toolAnswer).withReqId(reqId).toMap());
                            updateMessageStatus(reqId, "stopped", toolAnswer);
                        } else {
                            completeWithAnswer(reqId, userId, question, toolAnswer,
                                    config.provider, config.model, startTime);
                        }
                        return;
                    }
                }

                // LLM 流式调用（意图驱动温度 + 思考链展示）
                boolean enableThinking = isComplexIntent(intent);
                List<LLMMessage> effectiveHistory = history;
                // 知识问答/任务类问题：自动检索知识库，RAG 索引增强生成
                if (shouldAutoRag(intent, question)) {
                    String ragContext = buildChatRagContext(question);
                    if (ragContext != null) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                        effectiveHistory.add(0, new LLMMessage("system", buildChatRagSystemPrompt(ragContext)));
                        log.info("[doPersonalStream] req_id={} 知识库RAG增强命中 kb={} ctxLen={}",
                                reqId, chatRagKbId, ragContext.length());
                    }
                }
                if (enableThinking) {
                    if (effectiveHistory == history) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                    }
                    effectiveHistory.add(0, new LLMMessage("system",
                            "如果问题复杂需要推理分析，请先把分析路径写在 <thinking>...</thinking> 标签中，"
                            + "再给出最终回答。简单问题直接回答即可，不需要 <thinking> 标签。"));
                }

                // Step2: 长期事实记忆召回注入（Milvus user_memory），让回答贴合用户偏好
                java.util.List<String> memoryFacts = recallUserFacts(userId, question);
                if (!memoryFacts.isEmpty()) {
                    if (effectiveHistory == history) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                    }
                    effectiveHistory.add(0, new LLMMessage("system", buildFactMemoryPrompt(memoryFacts)));
                    log.info("[doPersonalStream] req_id={} 长期记忆注入 {} 条", reqId, memoryFacts.size());
                }

                // Step3: 技能库注入（Agent 自进化沉淀的技能，供直接套用）
                String skillPrompt = buildSkillSystemPrompt(question);
                if (skillPrompt != null && !skillPrompt.isBlank()) {
                    if (effectiveHistory == history) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                    }
                    effectiveHistory.add(0, new LLMMessage("system", skillPrompt));
                    log.info("[doPersonalStream] req_id={} 技能库注入命中", reqId);
                }

                final String topic = "/topic/user." + userId;
                StringBuilder answerCollector = new StringBuilder();

                String fullAnswer;
                if (enableThinking) {
                    // 思考链模式：用 ThinkingStreamParser 分离思考过程与回答
                    ThinkingStreamParser parser = new ThinkingStreamParser(
                            thinkingToken -> {
                                if (streamStopManager.getOrDefault(reqId).get()) return;
                                broadcastService.broadcast(topic,
                                        WsMessage.thinkingToken(thinkingToken).withReqId(reqId).toMap());
                            },
                            answerToken -> {
                                if (streamStopManager.getOrDefault(reqId).get()) return;
                                answerCollector.append(answerToken);
                                broadcastService.broadcast(topic,
                                        WsMessage.streamToken(answerToken).withReqId(reqId).toMap());
                            },
                            () -> {
                                broadcastService.broadcast(topic,
                                        WsMessage.thinkingStart().withReqId(reqId).toMap());
                            }
                    );

                    fullAnswer = llmInvoker.invokeStream(config, effectiveHistory, temperature,
                            "personal", llmConfig.getBaseUrl(), llmConfig.getApiKey(),
                            token -> {
                                if (streamStopManager.getOrDefault(reqId).get()) return;
                                parser.feed(token);
                            });
                    parser.flush();
                } else {
                    // 非思考链模式：直接流式输出（无延迟）
                    fullAnswer = llmInvoker.invokeStream(config, effectiveHistory, temperature,
                            "personal", llmConfig.getBaseUrl(), llmConfig.getApiKey(),
                            token -> {
                                if (streamStopManager.getOrDefault(reqId).get()) return;
                                answerCollector.append(token);
                                broadcastService.broadcast(topic,
                                        WsMessage.streamToken(token).withReqId(reqId).toMap());
                            });
                }

                // 思考链模式下 fullAnswer 含 <thinking> 标签，用 answerCollector 获取纯净回答
                String cleanAnswer = (!answerCollector.isEmpty())
                        ? answerCollector.toString()
                        : fullAnswer;

                if (isStopped(reqId)) {
                    broadcastService.broadcast("/topic/user." + userId,
                            WsMessage.stopped(cleanAnswer).withReqId(reqId).toMap());
                    updateMessageStatus(reqId, "stopped", cleanAnswer);
                } else {
                    completeWithAnswer(reqId, userId, question, cleanAnswer,
                            config.provider, config.model, startTime);
                }
            } catch (Exception ex) {
                log.error("流式调用失败", ex);
                broadcastService.broadcast("/topic/user." + userId,
                        WsMessage.error("生成失败: " + ex.getMessage()).withReqId(reqId).toMap());
            } finally {
                streamStopManager.remove(reqId);
            }
        }, modelExecutor);
    }

    /**
     * 群聊并发竞速：多个模型并发调用，首个完成者推送结果（意图驱动温度）。
     */
    private void doGroupConcurrent(String reqId, Long userId, String question,
                                    List<ModelConfig> configs, List<LLMMessage> history,
                                    long startTime, IntentResult intent) {
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicInteger finishedCount = new AtomicInteger(0);
        int totalModels = configs.size();
        @SuppressWarnings("PMD.UnusedLocalVariable")
        boolean isPrivate = false;
        double temperature = intent != null ? intentRouting.temperatureFor(intent.category()) : 0.7;
        log.info("[doGroupConcurrent] req_id={} userId={} models={} intent={} temp={}",
                reqId, userId, totalModels, intentLabel(intent), temperature);

        // 知识问答/任务类问题：自动检索知识库，RAG 索引增强（检索一次，注入所有并发模型）
        final List<LLMMessage> historyForCall;
        if (shouldAutoRag(intent, question)) {
            String ragContext = buildChatRagContext(question);
            if (ragContext != null && history != null) {
                List<LLMMessage> ragEnhanced = new java.util.ArrayList<>(history);
                ragEnhanced.add(0, new LLMMessage("system", buildChatRagSystemPrompt(ragContext)));
                historyForCall = ragEnhanced;
                log.info("[doGroupConcurrent] req_id={} 知识库RAG增强命中 kb={} ctxLen={}",
                        reqId, chatRagKbId, ragContext.length());
            } else {
                historyForCall = history;
            }
        } else {
            historyForCall = history;
        }

        for (ModelConfig config : configs) {
            CompletableFuture<LLMResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    String answer;
                    if (historyForCall != null) {
                        answer = llmInvoker.invoke(config, history, temperature, "chat",
                                llmConfig.getBaseUrl(), llmConfig.getApiKey());
                    } else {
                        answer = llmInvoker.invoke(config, question, temperature, "chat",
                                llmConfig.getBaseUrl(), llmConfig.getApiKey());
                    }
                    return new LLMResult(config, answer);
                } catch (Exception ex) {
                    throw new LLMCallException(config.model, "群聊调用失败", ex);
                }
            }, modelExecutor);

            future.handle((result, ex) -> {
                int finished = finishedCount.incrementAndGet();
                if (ex != null) {
                    log.error("模型 {} 调用失败: {}", config.model,
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                    llmCallRecorder.record(config.provider, config.model, "chat", false,
                            System.currentTimeMillis() - startTime, 0);
                } else {
                    log.info("模型 {} 返回成功, answer长度={}", config.model,
                            result.answer != null ? result.answer.length() : 0);
                    if (completed.compareAndSet(false, true)) {
                        log.info("抢到首发, reqId={}", reqId);
                        try {
                            completeWithAnswer(reqId, userId, question, result.answer,
                                    result.config.provider, result.config.model, startTime);
                            ragClient.saveMemoryAsync("chat", userId, question, result.answer);
                            ragClient.saveFactsAsync("chat", userId, question, result.answer);
                        } catch (Exception e) {
                            log.error("completeWithAnswer 失败", e);
                        }
                    }
                }
                if (finished == totalModels && !completed.get()) {
                    log.warn("所有 {} 个模型都失败了, reqId={}", totalModels, reqId);
                    broadcastService.broadcast("/topic/user." + userId,
                            WsMessage.error("所有模型调用均失败").withReqId(reqId).toMap());
                }
                return null;
            });
        }
    }

    /** 更新消息状态（停止时使用） */
    private void updateMessageStatus(String reqId, String status, String answer) {
        Message msg = messageRepository.findByReqId(reqId);
        if (msg != null) {
            try {
                msg.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            } catch (JsonProcessingException e) {
                msg.answerJson = "{\"answer\":\"\"}";
            }
            msg.status = status;
            messageRepository.updateByReqId(msg);
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
        } catch (DataAccessException ex) {
            log.warn("[WARN] regenerate 插入消息记录失败: {}", ex.getMessage());
        }

        log.info("[INFO] regenerate oldReqId={} newReqId={} userId={}", oldReqId, newReqId, userId);
        process(payload);
    }

    public void processWithFile(String reqId, Long userId, String question, String fileName, byte[] fileContent, String mimeType) {
        try {
            log.info("[INFO] processWithFile: reqId={}, fileName={}, mimeType={}", reqId, fileName, mimeType);
            final String lowerName = fileName != null ? fileName.toLowerCase(Locale.ROOT) : "";
            final boolean isImage = mimeType != null && mimeType.startsWith("image/");
            @SuppressWarnings("unused")
            final boolean isExcel = lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls");
            @SuppressWarnings("unused")
            final boolean isPpt = lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt");

            final String fileTextContent;
            final String fileBase64;

            if (isImage) {
                fileBase64 = Base64.getEncoder().encodeToString(fileContent);
                fileTextContent = null;
            } else {
                fileBase64 = null;
                String extracted = fileContentExtractor.extract(fileContent, fileName);
                if (extracted.isEmpty()) {
                    extracted = new String(fileContent, StandardCharsets.UTF_8);
                }
                if (extracted.length() > 30000) extracted = extracted.substring(0, 30000) + "\n...[已截断]";
                fileTextContent = extracted;
            }

            List<ModelConfig> allConfigs = modelRouter.loadChatModels(llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getApiKey());

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
                    Long boundModelId = modelRouter.getPersonalModelId(userId);
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

            final List<LLMMessage> fileHistoryMessages = chatHistoryBuilder.buildFile(
                    userId, question, fileName, fileTextContent, isImage, fileBase64, mimeType);

            List<CompletableFuture<?>> futures = new ArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            for (ModelConfig config : configs) {
                CompletableFuture<?> fullFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        String answer = llmInvoker.invoke(config, fileHistoryMessages, 0.7, "personal",
                                llmConfig.getBaseUrl(), llmConfig.getApiKey());
                        return new LLMResult(config, answer);
                    } catch (Exception ex) {
                        log.error("[ERROR] processWithFile 模型调用失败 [{}/{}]: {}", config.provider, config.model, ex.getMessage());
                        return null;
                    }
                }, modelExecutor).thenAccept(result -> {
                    if (result != null && completed.compareAndSet(false, true)) {
                        modelRouter.savePersonalModelId(userId, result.config.id);
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
            log.error("ChatProcessor processWithFile 失败", ex);
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error(ex.getMessage()).withReqId(reqId).toMap());
        }
    }



    public void completeWithAnswer(String reqId, Long userId, String question, String answer, String provider, String model, long startTime) {
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
        } catch (DataAccessException ex) {
            log.warn("[WARN] Redis write failed: {}", ex.getMessage());
        }

        // 保存对话记忆（异步 fire-and-forget）
        ragClient.saveMemoryAsync("personal", userId, question, answer);
        // L2: 异步抽取用户关键事实存 Milvus user_memory
        ragClient.saveFactsAsync("personal", userId, question, answer);

        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", answer));
            } catch (JsonProcessingException e) {
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

            // 触发知识图谱抽取（异步，失败不阻塞主流程；经 GraphClient 跨进程调 chat-llm）
            if (graphClient != null && m.id != null) {
                try {
                    graphClient.extractAndSaveAsync(m.id, question, answer, "chat");
                } catch (Exception ex) {
                    log.warn("[KnowledgeGraph] 触发知识抽取失败 messageId={}: {}", m.id, ex.getMessage());
                }
            }
        }
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
        } catch (NoSuchAlgorithmException e) {
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

    // ───────────── 意图识别辅助（三层漏斗） ─────────────

    /**
     * 通过三层漏斗识别意图：
     *   L1 规则层（关键词/正则/状态机）→ L2 上下文语义匹配 → L3 LLM 分类
     * 各层失败自动降级，不阻塞主流程。
     */
    private IntentResult recognizeIntent(String question, String scene) {
        if (funnelEngine == null || question == null || question.isBlank()) {
            return IntentResult.unknown();
        }
        try {
            var result = funnelEngine.recognize(question, scene, null, null);
            if (result.isKnown()) {
                log.debug("[IntentFunnel] scene={} source={} intent={} latency={}ms",
                         scene, result.source(), result.intent().category(), result.latencyMs());
            }
            return result.intent();
        } catch (Exception e) {
            log.debug("[IntentFunnel] 识别异常 scene={} error={}", scene, e.getMessage());
            return IntentResult.unknown();
        }
    }

    /** 意图的日志友好名称 */
    private String intentLabel(IntentResult intent) {
        if (intent == null || intentRouting == null) return "N/A";
        return intentRouting.label(intent.category());
    }

    /** 判断意图是否需要展示思考链（复杂推理类） */
    private boolean isComplexIntent(IntentResult intent) {
        if (intent == null) return false;
        IntentCategory c = intent.category();
        return c == IntentCategory.REASONING
            || c == IntentCategory.CODE_GENERATION
            || c == IntentCategory.KNOWLEDGE_QA
            || c == IntentCategory.TASK_EXECUTION;
    }

    /**
     * 是否需要自动检索知识库增强回答。
     *
     * <p>三层判定：</p>
     * 1. 开关/配置：启用且配置了默认知识库；
     * 2. 意图判定：知识问答（KNOWLEDGE_QA）或任务执行（TASK_EXECUTION）——概念/资料性查询；
     * 3. 可查性判定：排除实时数据类（天气、时间、新闻、行情、比分）与个人数据类（我的订单/消息）——
     *    知识库中不存在此类内容，检索只会浪费一次 Embedding，改由工具或模型实时回答。
     *
     * <p>检索后还有相似度判定（buildChatRagContext：score &lt; threshold 的片段丢弃），
     * 三层都不命中时完全回退普通回答。</p>
     */
    private boolean shouldAutoRag(IntentResult intent, String question) {
        if (!chatRagEnabled || chatRagKbId <= 0 || ragClient == null) return false;
        if (intent == null || intent.category() == null) return false;
        IntentCategory c = intent.category();
        if (c != IntentCategory.KNOWLEDGE_QA && c != IntentCategory.TASK_EXECUTION) return false;
        // 实时/个人数据类问题知识库没有答案，跳过检索
        return !isRealTimeOrPersonalQuery(question);
    }

    /**
     * 判断问题是否为实时数据/个人数据类查询（知识库中不存在此类内容）。
     * <p>实时：天气、时间、新闻、金融行情、体育比分；个人：订单/账户/消息等。</p>
     */
    private boolean isRealTimeOrPersonalQuery(String question) {
        if (question == null || question.isBlank()) return false;
        for (java.util.regex.Pattern p : REALTIME_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        for (java.util.regex.Pattern p : PERSONAL_PATTERNS) {
            if (p.matcher(question).find()) return true;
        }
        return false;
    }

    /**
     * 检索默认知识库，构建 RAG 参考资料（无命中返回 null，检索失败不影响主流程）。
     */
    private String buildChatRagContext(String question) {
        if (question == null || question.isBlank()) return null;
        try {
            List<RagClient.SearchResult> results = ragClient.search(chatRagKbId, question, chatRagTopK);
            if (results == null || results.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            int total = 0;
            for (RagClient.SearchResult r : results) {
                if (r.score() < chatRagScoreThreshold) continue;
                if (total + r.text().length() > chatRagMaxChars) break;
                sb.append("--- 资料（相似度 ").append(String.format("%.2f", r.score())).append("）---\n");
                sb.append(r.text()).append("\n\n");
                total += r.text().length();
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            log.warn("[ChatRAG] 知识库检索失败 kb={} err={}", chatRagKbId, e.getMessage());
            return null;
        }
    }

    /** 构建 RAG 增强的 system prompt（引导依据知识库资料作答） */
    private String buildChatRagSystemPrompt(String context) {
        return "以下是用户知识库中检索到的参考资料。回答时请优先依据参考资料作答，"
                + "如果参考资料不足以回答，可结合你的知识回答并简要说明。\n\n"
                + "【参考资料】\n" + context;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Step2 长期事实记忆 / Step3 技能注入
    // ═══════════════════════════════════════════════════════════════════

    /** 召回用户长期事实记忆（Milvus user_memory），失败返回空列表 */
    private java.util.List<String> recallUserFacts(Long userId, String question) {
        if (userId == null || userId <= 0 || question == null || question.isBlank()) return java.util.List.of();
        try {
            java.util.List<String> facts = ragClient.recallFacts(userId, question, factMemoryTopK);
            return facts != null ? facts : java.util.List.of();
        } catch (Exception e) {
            log.debug("[Memory] 事实记忆召回失败 user={}: {}", userId, e.getMessage());
            return java.util.List.of();
        }
    }

    /** 构建长期记忆注入 prompt */
    private String buildFactMemoryPrompt(java.util.List<String> facts) {
        return "以下是你对该用户的长期记忆事实（来自历史对话的提炼），回答时参考但不要逐字复述：\n"
                + String.join("\n- ", facts);
    }

    /**
     * 构建技能库注入 prompt（Agent 自进化沉淀的技能，供直接套用）。
     * 返回 null 表示无可注入技能。
     */
    private String buildSkillSystemPrompt(String question) {
        if (skillRegistry == null || !skillRegistry.hasSkills()) return null;
        java.util.List<com.example.chat.entity.Skill> skills = skillRegistry.allSkills();
        if (skills.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("以下是系统已沉淀的可复用技能。若当前问题与某技能适用场景匹配，"
                + "请直接调用该技能（按其代码/步骤执行），不要重复发明。\n\n");
        int injected = 0;
        for (com.example.chat.entity.Skill s : skills) {
            if (injected >= 3) break;
            sb.append("【技能 ").append(s.name).append("】").append(s.language).append('\n');
            if (s.description != null && !s.description.isBlank()) {
                sb.append("适用场景: ").append(s.description).append('\n');
            }
            if (s.triggerPrompt != null && !s.triggerPrompt.isBlank()) {
                sb.append("触发说明: ").append(s.triggerPrompt).append('\n');
            }
            if (s.code != null && !s.code.isBlank()) {
                sb.append("代码:\n```").append(s.language).append('\n').append(s.code).append("\n```\n");
            }
            sb.append('\n');
            injected++;
        }
        return sb.toString();
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

}
