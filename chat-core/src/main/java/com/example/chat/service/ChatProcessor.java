package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.intent.IntentCategory;
import com.example.chat.intent.IntentResult;
import com.example.chat.intent.IntentRoutingHelper;
import com.example.chat.intent.funnel.IntentFunnelEngine;
import com.example.chat.intent.funnel.ThinkingStreamParser;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.dao.DataAccessException;

@Service
@SuppressWarnings("PMD.CyclomaticComplexity") // 类级复杂度来自字段初始化器/流式匿名类，业务方法已分别豁免
public class ChatProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatProcessor.class);
    private final MessageRepository messageRepository;
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
    private final ChatRagEnhancer chatRagEnhancer;
    private final ChatCacheManager chatCacheManager;

    /** RAG 客户端（通过 /internal/rag/* 调用 chat-llm 的知识库检索与对话记忆） */
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.chat.client.RagClient ragClient;

    /** LangChain4j 个人对话服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.langchain4j.LangChain4jPersonalChatService langChain4jPersonalChatService;

    /** 是否启用 LangChain4j 个人对话模式 */
    @org.springframework.beans.factory.annotation.Value("${app.langchain4j.personal.enabled:false}")
    private boolean langChain4jPersonalEnabled;

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

    /** 流式生成停止管理 */
    public void requestStop(String reqId) {
        streamStopManager.requestStop(reqId);
    }

    public boolean isStopped(String reqId) {
        return streamStopManager.isStopped(reqId);
    }

    public ChatProcessor(MessageRepository messageRepository,
                         ObjectMapper objectMapper,
                         BroadcastService broadcastService,
                         LLMCallRecorder llmCallRecorder,
                         LLMInvoker llmInvoker,
                         ChatHistoryBuilder chatHistoryBuilder,
                         ModelRouter modelRouter,
                         FileContentExtractor fileContentExtractor,
                         StreamStopManager streamStopManager,
                         LlmConfigProperties llmConfig,
                         ChatRagEnhancer chatRagEnhancer,
                         ChatCacheManager chatCacheManager) {
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.llmCallRecorder = llmCallRecorder;
        this.llmInvoker = llmInvoker;
        this.chatHistoryBuilder = chatHistoryBuilder;
        this.modelRouter = modelRouter;
        this.fileContentExtractor = fileContentExtractor;
        this.streamStopManager = streamStopManager;
        this.llmConfig = llmConfig;
        this.chatRagEnhancer = chatRagEnhancer;
        this.chatCacheManager = chatCacheManager;
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
        if (chatCacheManager.hitAndServe(reqId, userId, question)) {
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
    @SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.CognitiveComplexity", "PMD.NPathComplexity"})
    // == 为引用比较：判断 effectiveHistory 是否需防御拷贝；流式链路多分支（意图路由/记忆/技能/思考链）拆分无收益
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
                // 个人对话强制开启思考链：每句问题都展示推理过程
                boolean enableThinking = true;
                List<LLMMessage> effectiveHistory = history;
                // 实时数据类问题拦截：注入 system prompt 明确告知 LLM 无法获取实时数据
                // 避免带历史上下文时 LLM 把"天气怎样"误解为上下文相关话题
                if (chatRagEnhancer.isRealTimeOrPersonalQuery(question)) {
                    if (effectiveHistory == history) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                    }
                    effectiveHistory.add(0, new LLMMessage("system",
                            "用户问的是实时数据类问题（如天气、时间、新闻、行情等）。"
                            + "你是 AI 助手，无法获取实时数据，请直接告知用户你无法查询实时信息，"
                            + "不要结合历史对话上下文猜测或编造实时数据。"));
                    log.info("[doPersonalStream] req_id={} 实时数据问题拦截，注入实时声明", reqId);
                }
                // 知识问答/任务类问题：自动检索知识库，RAG 索引增强生成
                if (chatRagEnhancer.shouldAutoRag(intent, question)) {
                    String ragContext = chatRagEnhancer.buildContext(question);
                    if (ragContext != null) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                        effectiveHistory.add(0, new LLMMessage("system", chatRagEnhancer.buildSystemPrompt(ragContext)));
                        log.info("[doPersonalStream] req_id={} 知识库RAG增强命中 kb={} ctxLen={}",
                                reqId, chatRagEnhancer.getKbId(), ragContext.length());
                    }
                }
                if (enableThinking) {
                    if (effectiveHistory == history) {
                        effectiveHistory = new java.util.ArrayList<>(history);
                    }
                    effectiveHistory.add(0, new LLMMessage("system",
                            "请在回答前必须先用 <thinking>...</thinking> 标签写出你的推理分析过程。"
                            + "即使是简单问题也要简要说明你的思考逻辑，然后再给出最终回答。"));
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
                String skillPrompt = buildSkillSystemPrompt();
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
                    // 思考链模式：思考过程走 thinking_token 事件（前端灰色展示，done 后清除）
                    // 回答走 stream_token 事件（正常颜色展示）
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
                            () -> {}
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
                String cleanAnswer = answerCollector.isEmpty()
                        ? fullAnswer
                        : answerCollector.toString();

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
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity"})
    // 群聊并发多模型汇总：竞态结果合并/流式转发/异常兜底，拆分破坏并发状态管理
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
        // 实时数据类问题拦截：注入 system prompt 明确告知 LLM 无法获取实时数据
        if (chatRagEnhancer.isRealTimeOrPersonalQuery(question)) {
            List<LLMMessage> realtimeHistory = new java.util.ArrayList<>(history);
            realtimeHistory.add(0, new LLMMessage("system",
                    "用户问的是实时数据类问题（如天气、时间、新闻、行情等）。"
                    + "你是 AI 助手，无法获取实时数据，请直接告知用户你无法查询实时信息，"
                    + "不要结合历史对话上下文猜测或编造实时数据。"));
            historyForCall = realtimeHistory;
            log.info("[doGroupConcurrent] req_id={} 实时数据问题拦截", reqId);
        } else if (chatRagEnhancer.shouldAutoRag(intent, question)) {
            String ragContext = chatRagEnhancer.buildContext(question);
            if (ragContext != null && history != null) {
                List<LLMMessage> ragEnhanced = new java.util.ArrayList<>(history);
                ragEnhanced.add(0, new LLMMessage("system", chatRagEnhancer.buildSystemPrompt(ragContext)));
                historyForCall = ragEnhanced;
                log.info("[doGroupConcurrent] req_id={} 知识库RAG增强命中 kb={} ctxLen={}",
                        reqId, chatRagEnhancer.getKbId(), ragContext.length());
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

    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.NPathComplexity", "PMD.ConfusingTernary"})
    // 文件问答链路：类型识别/模型链选择/内容注入/流式响应，拆分会割裂降级优先级
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
                        if (bound.isEmpty()) {
                            configs = allConfigs;
                        } else {
                            configs = bound;
                        }
                    } else {
                        List<ModelConfig> textConfigs = allConfigs.stream()
                                .filter(c -> "qwen".equalsIgnoreCase(c.provider))
                                .toList();
                        if (textConfigs.isEmpty()) {
                            configs = allConfigs;
                        } else {
                            configs = textConfigs;
                        }
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

        chatCacheManager.save(question, provider, model, answer);

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
    @SuppressWarnings("PMD.UnusedPrivateMethod") // 被 ChatProcessorTest 反射调用
    private boolean isComplexIntent(IntentResult intent) {
        if (intent == null) return false;
        IntentCategory c = intent.category();
        return c == IntentCategory.REASONING
            || c == IntentCategory.CODE_GENERATION
            || c == IntentCategory.KNOWLEDGE_QA
            || c == IntentCategory.TASK_EXECUTION;
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
        return "用户记忆：\n- " + String.join("\n- ", facts);
    }

    /**
     * 构建技能库注入 prompt（Agent 自进化沉淀的技能，供直接套用）。
     * 返回 null 表示无可注入技能。
     */
    private String buildSkillSystemPrompt() {
        if (skillRegistry == null || !skillRegistry.hasSkills()) return null;
        java.util.List<com.example.chat.entity.Skill> skills = skillRegistry.allSkills();
        if (skills.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("以下是系统已沉淀的可复用技能。若当前问题与某技能适用场景匹配，"
                + "请直接调用该技能（按其代码/步骤执行），不要重复发明。\n\n");
        int injected = 0;
        for (com.example.chat.entity.Skill s : skills) {
            if (injected >= 3) break;
            sb.append("【技能 ").append(s.name).append('】').append(s.language).append('\n');
            if (s.description != null && !s.description.isBlank()) {
                sb.append("适用场景: ").append(s.description).append('\n');
            }
            if (s.triggerPrompt != null && !s.triggerPrompt.isBlank()) {
                sb.append("触发说明: ").append(s.triggerPrompt).append('\n');
            }
            if (s.code != null && !s.code.isBlank()) {
                sb.append("代码:\n```").append(s.language).append('\n').append(s.code).append('\n').append("```").append('\n');
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
