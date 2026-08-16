package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Service
public class DebateProcessor {
    private static final Logger log = LoggerFactory.getLogger(DebateProcessor.class);
    /** 上下文窗口控制：辩论记录仅保留最近 MAX_FULL_ROUNDS 轮全文，更早轮次压缩为摘要，防止长辩论 token 无限膨胀 */
    private static final int MAX_FULL_ROUNDS = 2;
    /** 摘要轮次中单条回答保留的最大字符数，超出截断并加省略号 */
    private static final int SUMMARY_ANSWER_MAX_CHARS = 100;
    /** 全文轮次中单条回答的防御性上限，超出截断（正常不会触发） */
    private static final int FULL_ANSWER_MAX_CHARS = 1000;
    private final MessageRepository messageRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService debateExecutor;
    private final DebateRecordRepository debateRecordRepository;
    private final BroadcastService broadcastService;
    private final LLMInvoker llmInvoker;

    /** LangGraph4j 辩论图服务（可选注入） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.langgraph4j.DebateGraphService debateGraphService;

    /** 树状辩论处理器 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DebateTreeProcessor debateTreeProcessor;

    /** 知识图谱客户端（可选注入，失败不阻塞主流程；运行时已迁至 chat-llm） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.chat.client.GraphClient graphClient;

    /** 是否启用 LangGraph4j 辩论模式 */
    @org.springframework.beans.factory.annotation.Value("${app.langgraph4j.debate.enabled:false}")
    private boolean langGraph4jDebateEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.llm.api-key:}")
    private String defaultApiKey;

    public DebateProcessor(MessageRepository messageRepository,
                           ModelConfigRepository modelConfigRepository,
                           ObjectMapper objectMapper,
                           DebateRecordRepository debateRecordRepository,
                           BroadcastService broadcastService,
                           LLMInvoker llmInvoker) {
        this.messageRepository = messageRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.debateRecordRepository = debateRecordRepository;
        this.broadcastService = broadcastService;
        this.llmInvoker = llmInvoker;
        this.debateExecutor = com.example.chat.config.ThreadPoolFactory.create(
                6, 12, 20, "debate-worker");
    }

    @SuppressWarnings("PMD.NPathComplexity") // 辩论编排：payload 解析/双方模型选取/流式广播/落库，拆分破坏单线流程
    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        Long debateRecordId = payload.get("debate_record_id") == null ? null : Long.parseLong(payload.get("debate_record_id").toString());
        String userName = payload.get("user_name") != null ? payload.get("user_name").toString() : "";
        String mode = payload.get("mode") != null ? payload.get("mode").toString() : "";
        int totalRounds = parseRounds(payload.get("rounds"));
        int modelCount = parseModelCount(payload.get("model_count"));
        boolean treeMode = "tree".equals(mode);
        // 深度思考开关（前端「深度思考」按钮，仅对豆包等原生思考模型生效）
        boolean deepThinking = "true".equals(String.valueOf(payload.get("deep_thinking")));

        // 树状模式排除本地自研(ollama)模型：多轮串行且每轮等最慢分支，本地推理(12s+/次)会拖垮整场
        Map<Long, ModelConfig> modelMap = resolveDebateModels(
                modelConfigRepository.findAllEnabledByType("chat"), modelCount, treeMode);
        if (modelMap.isEmpty()) {
            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.error("可用的 chat 模型不足 3 个，无法开启辩论").withReqId(reqId).toMap());
            return;
        }
        final ModelConfig summaryModel = modelMap.get(0L); // 由第一位辩论方兼任整合模型

        // 树状模式：语义拆解 → 多视角并行辩论 → 汇总（DebateTreeProcessor 内部自行广播 start）
        if (treeMode) {
            debateTreeProcessor.process(reqId, userId, question, modelMap, summaryModel, deepThinking);
            return;
        }

        // LangGraph4j 模式：图式工作流编排辩论（DebateGraphService 内部自行广播 start，编号与其图分支一致）
        if (langGraph4jDebateEnabled && debateGraphService != null) {
            runLangGraph4jDebate(reqId, userId, question, summaryModel, debateRecordId, totalRounds);
            return;
        }

        // 传统线性辩论：先广播模型信息（整合模型 id = 参与者数量，与流式事件编号一致）
        broadcastModelInfo(userId, reqId, modelMap, summaryModel);

        debateExecutor.submit(() -> {
            // 深度思考开关透传（主线程覆盖汇总调用，并发模型在各自线程单独设置）
            LLMInvoker.setDeepThinking(deepThinking);
            try {
                runDebate(reqId, userId, question, modelMap, summaryModel, debateRecordId, userName, totalRounds, deepThinking);
            } catch (Exception e) {
                log.error("[ERROR] DebateProcessor: {}", e.getMessage(), e);
                broadcastService.broadcast("/topic/debate." + userId,
                        WsMessage.error(e.getMessage()).withReqId(reqId).toMap());
            } finally {
                LLMInvoker.clearDeepThinking();
            }
        });
    }

    /** 从启用的 chat 模型中随机选取 modelCount 个作为辩论方，动态分配 id 0..N-1（随机多模型并行辩论）。可用模型不足 3 个返回空。
     *  excludeLocal=true（树状模式）时排除本地自研(ollama)模型：本地推理慢，树状每视角多轮串行会被整体拖慢；排除后不足 3 个则回退全池。 */
    private Map<Long, ModelConfig> resolveDebateModels(List<ModelConfig> chatModels, int modelCount, boolean excludeLocal) {
        if (chatModels == null || chatModels.size() < 3) return Collections.emptyMap();
        List<ModelConfig> pool = new ArrayList<>();
        for (ModelConfig m : chatModels) {
            if (!excludeLocal || !"ollama".equalsIgnoreCase(m.getProvider())) pool.add(m);
        }
        if (pool.size() < 3) pool = new ArrayList<>(chatModels); // 回退全池，保证能开辩论
        List<ModelConfig> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled); // 每场辩论随机抽取，避免固定阵容
        int count = Math.max(3, Math.min(modelCount, shuffled.size()));
        Map<Long, ModelConfig> map = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            map.put((long) i, shuffled.get(i));
        }
        return map;
    }

    private void broadcastModelInfo(Long userId, String reqId, Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {
        List<Map<String, Object>> models = new ArrayList<>();
        modelMap.forEach((id, cfg) ->
                models.add(Map.of("id", id, "name", displayName(cfg))));
        // 整合模型 id = 参与者数量（约定：models 数组最后一个为整合模型）
        models.add(Map.of("id", (long) modelMap.size(), "name", displayName(summaryModel)));
        broadcastService.broadcast("/topic/debate." + userId,
                WsMessage.of("start").withReqId(reqId).with("models", models).toMap());
    }

    /** 模型展示名（provider 中文 + 自研模型附加模型名） */
    private String displayName(ModelConfig cfg) {
        return ModelRouter.modelDisplayName(cfg.provider, cfg.model);
    }

    /** LangGraph4j 编排模式 */
    private void runLangGraph4jDebate(String reqId, Long userId, String question,
                                       ModelConfig summaryModel, Long debateRecordId, int totalRounds) {
        debateExecutor.submit(() -> {
            try {
                com.example.chat.langgraph4j.DebateState result = debateGraphService.execute(reqId, userId, question, totalRounds);
                String summary = result.getSummary() != null ? result.getSummary() : "";
                persistDebateResults(reqId, question, summary, summaryModel, debateRecordId, null);
            } catch (Exception e) {
                log.error("[LangGraph4j] 辩论图执行失败: {}", e.getMessage(), e);
                broadcastService.broadcast("/topic/debate." + userId,
                        WsMessage.error("辩论图执行失败: " + e.getMessage()).withReqId(reqId).toMap());
            }
        });
    }

    private void runDebate(String reqId, Long userId, String question, Map<Long, ModelConfig> modelMap,
                           ModelConfig summaryModel, Long debateRecordId, String userName, int totalRounds,
                           boolean deepThinking) {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        List<Long> debateOrder = new ArrayList<>(modelMap.keySet()); // 0..N-1 动态

        for (int round = 1; round <= totalRounds; round++) {
            final int currentRound = round;
            List<Map<String, String>> roundResponses = Collections.synchronizedList(new ArrayList<>());
            allRounds.add(roundResponses);

            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_start").withReqId(reqId).with("round", round)
                            .with("model_ids", debateOrder).toMap());

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long modelId : debateOrder) {
                ModelConfig config = modelMap.get(modelId);
                String displayName = displayName(config);
                String prompt = buildDebatePrompt(question, allRounds, currentRound, displayName);

                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                    // 并发模型在各自线程执行，需单独透传深度思考开关
                    LLMInvoker.setDeepThinking(deepThinking);
                    try {
                        String answer = llmInvoker.invokeStream(config,
                                List.of(LLMMessage.user(prompt)),
                                0.7, "debate", null, defaultApiKey,
                                token -> {
                                    if (userId != null && reqId != null) {
                                        broadcastService.broadcast("/topic/debate." + userId,
                                                WsMessage.streamToken(token)
                                                        .withReqId(reqId).with("model_id", modelId).toMap());
                                    }
                                });
                        return Map.of("model_id", String.valueOf(modelId), "provider", displayName, "answer", answer);
                    } catch (Exception e) {
                        return Map.of("model_id", String.valueOf(modelId), "provider", displayName, "answer", "[" + displayName + " 调用失败]");
                    } finally {
                        LLMInvoker.clearDeepThinking();
                    }
                }, debateExecutor).thenAccept(result -> {
                    roundResponses.add(result);
                    try {
                        broadcastService.broadcast("/topic/debate." + userId,
                                WsMessage.of("round_response").withReqId(reqId)
                                        .with("round", currentRound).with("model_id", modelId)
                                        .with("provider", result.get("provider"))
                                        .with("answer", result.get("answer")).toMap());
                    } catch (Exception ex) {
                        log.warn("[WARN] WS send failed: {}", ex.getMessage());
                    }
                });
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_end").withReqId(reqId).with("round", currentRound).toMap());
        }

        broadcastService.broadcast("/topic/debate." + userId,
                WsMessage.of("synthesizing").withReqId(reqId)
                        .with("synthesizer", displayName(summaryModel)).toMap());

        String synthesisPrompt = buildSynthesisPrompt(question, allRounds, displayName(summaryModel), totalRounds);

        try {
            String finalAnswer = llmInvoker.invokeStream(summaryModel,
                    List.of(LLMMessage.user(synthesisPrompt)),
                    0.7, "debate", null, defaultApiKey,
                    token -> {
                        if (userId != null && reqId != null) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                            WsMessage.streamToken(token)
                                                    .withReqId(reqId).with("model_id", modelMap.size()).toMap());
                        }
                    });

            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", finalAnswer).toMap());

            persistDebateResults(reqId, question, finalAnswer, summaryModel, debateRecordId, userName);
        } catch (Exception e) {
            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.error("最终整合失败: " + e.getMessage()).withReqId(reqId).toMap());
        }
    }

    /** 截断单条回答：超过 maxChars 时截断并加省略号 */
    private String truncate(String answer, int maxChars) {
        if (answer == null) return "";
        if (answer.length() <= maxChars) return answer;
        return answer.substring(0, maxChars) + "…";
    }

    /** 拼接辩论讨论记录（上下文窗口控制）：仅保留最近 MAX_FULL_ROUNDS 轮全文，更早轮次压缩为摘要。
     *  markSummary=false 时全部轮次保留全文（仅做防御性截断）。 */
    private void appendRoundHistory(StringBuilder sb, List<List<Map<String, String>>> allRounds, boolean markSummary) {
        int total = allRounds.size();
        int keepFrom = Math.max(0, total - MAX_FULL_ROUNDS);
        for (int r = 0; r < total; r++) {
            List<Map<String, String>> round = allRounds.get(r);
            if (round.isEmpty()) continue;
            boolean summarized = markSummary && r < keepFrom;
            sb.append("\n### 第 ").append(r + 1).append(" 轮讨论");
            if (summarized) sb.append("（摘要，原文较长已压缩）");
            sb.append("\n");
            for (Map<String, String> resp : round) {
                String answer = resp.get("answer");
                sb.append("**").append(resp.get("provider")).append("**: ")
                        .append(summarized ? truncate(answer, SUMMARY_ANSWER_MAX_CHARS)
                                           : truncate(answer, FULL_ANSWER_MAX_CHARS))
                        .append("\n\n");
            }
        }
    }

    private String buildDebatePrompt(String question, List<List<Map<String, String>>> allRounds, int currentRound, String myName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI辩论参与者，你的身份是「").append(myName).append("」。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");

        if (currentRound > 1) {
            sb.append("## 之前的讨论记录\n");
            appendRoundHistory(sb, allRounds, true);
        }

        if (currentRound == 1) {
            sb.append("## 你的任务\n");
            sb.append("这是第 1 轮讨论。请针对上述问题给出你的独立见解和分析。要求观点明确、论据充分。\n");
            sb.append("请注意：其他AI参与者也会回答同一个问题，你需要展示自己独特的视角。\n");
        } else {
            sb.append("## 你的任务\n");
            sb.append("这是第 ").append(currentRound).append(" 轮讨论。请阅读其他AI参与者的观点后：\n");
            sb.append("1. 对认同的观点进行补充和深化\n");
            sb.append("2. 对不认同的观点提出有理有据的反驳\n");
            sb.append("3. 综合各方观点，更新和完善自己的立场\n");
        }

        sb.append("\n请直接将返回结果限制在50个字以内，不要复述讨论过程。");
        return sb.toString();
    }

    /** 持久化辩论结果：更新 Message、DebateRecord，触发知识图谱抽取 */
    private void persistDebateResults(String reqId, String question, String finalAnswer,
                                       ModelConfig summaryModel, Long debateRecordId, String userName) {
        try {
            String answerJson = objectMapper.writeValueAsString(Map.of("answer", finalAnswer));
            Message m = messageRepository.findByReqId(reqId);
            if (m != null) {
                m.answerJson = answerJson;
                m.status = "done";
                m.provider = summaryModel.provider;
                m.model = summaryModel.model;
                messageRepository.updateByReqId(m);
            }

            DebateRecord debateRecord = debateRecordRepository.findById(debateRecordId);
            if (debateRecord != null) {
                debateRecord.finalAnswer = finalAnswer;
                debateRecord.userName = userName;
                debateRecord.status = "completed";
                debateRecordRepository.updateAnswer(debateRecord);
            }

            // 触发知识图谱抽取（经 GraphClient 跨进程调 chat-llm）
            if (graphClient != null && m != null && m.id != null) {
                graphClient.extractAndSaveAsync(m.id, question, finalAnswer, "debate");
            }
        } catch (JsonProcessingException | DataAccessException ex) {
            log.warn("[Debate] 结果持久化失败: {}", ex.getMessage());
        }
    }

    private String buildSynthesisPrompt(String question, List<List<Map<String, String>>> allRounds, String myName, int totalRounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(myName).append("」，作为最终总结者，请综合以下").append(totalRounds).append("轮辩论内容，按照指定格式给出整合结论。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");
        sb.append("## ").append(totalRounds).append("轮辩论记录\n");

        appendRoundHistory(sb, allRounds, true);

        sb.append("## 输出格式要求\n");
        sb.append("请严格按照以下结构输出，每部分控制在30字以内：\n\n");
        sb.append("**【共识】** （各模型共同认同的核心观点）\n");
        sb.append("...\n\n");
        sb.append("**【差异】** （各模型的分歧或独特视角）\n");
        sb.append("...\n\n");
        sb.append("供您参考。");
        return sb.toString();
    }

    /** 解析辩论模型数：默认 3，范围 3-6（实际可用数以 resolveDebateModels 再收紧） */
    private int parseModelCount(Object v) {
        int n = 3;
        if (v != null) {
            try {
                n = Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // 非法值回退默认
            }
        }
        return Math.max(3, Math.min(6, n));
    }

    /** 解析辩论轮数：默认 3，范围 1-10（防滥用） */
    private int parseRounds(Object v) {
        int r = 3;
        if (v != null) {
            try {
                r = Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // 非法值回退默认
            }
        }
        return Math.max(1, Math.min(10, r));
    }

}
