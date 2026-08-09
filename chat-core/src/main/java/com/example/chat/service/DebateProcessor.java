package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.DebateRecord;
import com.example.chat.entity.Message;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class DebateProcessor {
    private static final Logger log = LoggerFactory.getLogger(DebateProcessor.class);
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

    /** 知识图谱服务（可选注入，失败不阻塞主流程） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KnowledgeGraphService knowledgeGraphService;

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
        this.debateExecutor = new java.util.concurrent.ThreadPoolExecutor(
                6, 12, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(20),
                r -> { Thread t = new Thread(r, "debate-worker"); t.setDaemon(true); return t; },
                new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
        );
    }

    public void process(Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = payload.get("user_id") == null ? 0L : Long.parseLong(payload.get("user_id").toString());
        String question = payload.get("question") == null ? "" : payload.get("question").toString();
        Long debateRecordId = payload.get("debate_record_id") == null ? null : Long.parseLong(payload.get("debate_record_id").toString());
        String userName = payload.get("user_name") != null ? payload.get("user_name").toString() : "";

        // 辩论固定使用三个 chat 模型：豆包、千问、DeepSeek（各自调用自家 chat 模型）
        // 最终整合模型固定为千问 chat，全程不出现智谱
        List<ModelConfig> chatModels = modelConfigRepository.findAllEnabledByType("chat");

        ModelConfig doubaoModel = chatModels.stream()
                .filter(m -> "doubao".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);
        ModelConfig qwenModel = chatModels.stream()
                .filter(m -> "qwen".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);
        ModelConfig deepseekModel = chatModels.stream()
                .filter(m -> "deepseek".equalsIgnoreCase(m.provider))
                .findFirst()
                .orElse(null);

        if (doubaoModel == null || qwenModel == null || deepseekModel == null) {
            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.error("需要豆包、千问、DeepSeek 三个 chat 模型均已启用").withReqId(reqId).toMap());
            return;
        }

        // 三个辩论模型：豆包、千问、DeepSeek
        Map<Long, ModelConfig> modelMap = new LinkedHashMap<>();
        modelMap.put(1L, doubaoModel);
        modelMap.put(2L, qwenModel);
        modelMap.put(3L, deepseekModel);

        // 整合模型固定为千问 chat
        final ModelConfig summaryModel = qwenModel;

        broadcastService.broadcast("/topic/debate." + userId,
                WsMessage.of("start").withReqId(reqId)
                        .with("models", List.of(
                                Map.of("id", 1, "name", ModelRouter.toDisplayName(modelMap.get(1L).provider)),
                                Map.of("id", 2, "name", ModelRouter.toDisplayName(modelMap.get(2L).provider)),
                                Map.of("id", 3, "name", ModelRouter.toDisplayName(modelMap.get(3L).provider)),
                                Map.of("id", 4, "name", ModelRouter.toDisplayName(summaryModel.provider))
                        )));

        // LangGraph4j 模式：图式工作流编排辩论
        // 节点内部已实时推送 round_start/stream_token/round_response/synthesizing/done 事件
        if (langGraph4jDebateEnabled && debateGraphService != null) {
            debateExecutor.submit(() -> {
                try {
                    com.example.chat.langgraph4j.DebateState result = debateGraphService.execute(reqId, userId, question);

                    String summary = result.getSummary() != null ? result.getSummary() : "";

                    // 保存辩论记录
                    if (debateRecordId != null) {
                        try {
                            com.example.chat.entity.DebateRecord record = new com.example.chat.entity.DebateRecord();
                            record.id = debateRecordId;
                            record.finalAnswer = summary;
                            record.status = "done";
                            debateRecordRepository.updateAnswer(record);
                        } catch (Exception ex) {
                            log.warn("[LangGraph4j] 辩论记录保存失败: {}", ex.getMessage());
                        }
                    }

                    // 更新消息记录
                    try {
                        String answerJson = objectMapper.writeValueAsString(Map.of("answer", summary));
                        Message m = messageRepository.findByReqId(reqId);
                        if (m != null) {
                            m.answerJson = answerJson;
                            m.status = "done";
                            m.provider = summaryModel.provider;
                            m.model = summaryModel.model;
                            messageRepository.updateByReqId(m);
                        }
                    } catch (Exception ex) {
                        log.warn("[LangGraph4j] 消息记录更新失败: {}", ex.getMessage());
                    }
                } catch (Exception e) {
                    log.error("[LangGraph4j] 辩论图执行失败: {}", e.getMessage(), e);
                    broadcastService.broadcast("/topic/debate." + userId,
                            WsMessage.error("辩论图执行失败: " + e.getMessage()).withReqId(reqId).toMap());
                }
            });
            return;
        }

        debateExecutor.submit(() -> {
            try {
                runDebate(reqId, userId, question, modelMap, summaryModel, debateRecordId, userName);
            } catch (Exception e) {
                log.error("[ERROR] DebateProcessor: {}", e.getMessage(), e);
                broadcastService.broadcast("/topic/debate." + userId,
                        WsMessage.error(e.getMessage()).withReqId(reqId).toMap());
            }
        });
    }

    private void runDebate(String reqId, Long userId, String question, Map<Long, ModelConfig> modelMap,
                           ModelConfig summaryModel, Long debateRecordId, String userName) {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        List<Long> debateOrder = List.of(1L, 2L, 3L);

        for (int round = 1; round <= 3; round++) {
            final int currentRound = round;
            List<Map<String, String>> roundResponses = Collections.synchronizedList(new ArrayList<>());
            allRounds.add(roundResponses);

            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_start").withReqId(reqId).with("round", round).toMap());

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Long modelId : debateOrder) {
                ModelConfig config = modelMap.get(modelId);
                String displayName = ModelRouter.toDisplayName(config.provider);
                String prompt = buildDebatePrompt(question, allRounds, currentRound, displayName);

                CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
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
                        .with("synthesizer", ModelRouter.toDisplayName(summaryModel.provider)).toMap());

        String synthesisPrompt = buildSynthesisPrompt(question, allRounds, ModelRouter.toDisplayName(summaryModel.provider));

        try {
            String finalAnswer = llmInvoker.invokeStream(summaryModel,
                    List.of(LLMMessage.user(synthesisPrompt)),
                    0.7, "debate", null, defaultApiKey,
                    token -> {
                        if (userId != null && reqId != null) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                            WsMessage.streamToken(token)
                                                    .withReqId(reqId).with("model_id", 4).toMap());
                        }
                    });

            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", finalAnswer).toMap());

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

            // 触发知识图谱抽取（异步，失败不阻塞主流程）
            if (knowledgeGraphService != null && m != null && m.id != null) {
                try {
                    knowledgeGraphService.extractAndSaveAsync(m.id, question, finalAnswer, "debate");
                } catch (Exception ex) {
                    log.warn("[KnowledgeGraph] 辩论知识抽取失败 msgId={}: {}", m.id, ex.getMessage());
                }
            }
        } catch (Exception e) {
            broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.error("最终整合失败: " + e.getMessage()).withReqId(reqId).toMap());
        }
    }

    private String buildDebatePrompt(String question, List<List<Map<String, String>>> allRounds, int currentRound, String myName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI辩论参与者，你的身份是「").append(myName).append("」。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");

        if (currentRound > 1) {
            sb.append("## 之前的讨论记录\n");
            for (int r = 0; r < allRounds.size(); r++) {
                List<Map<String, String>> round = allRounds.get(r);
                if (round.isEmpty()) continue;
                sb.append("\n### 第 ").append(r + 1).append(" 轮讨论\n");
                for (Map<String, String> resp : round) {
                    sb.append("**").append(resp.get("provider")).append("**: ").append(resp.get("answer")).append("\n\n");
                }
            }
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

    private String buildSynthesisPrompt(String question, List<List<Map<String, String>>> allRounds, String myName) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(myName).append("」，作为最终总结者，请综合以下3轮辩论内容，按照指定格式给出整合结论。\n\n");
        sb.append("## 原始问题\n").append(question).append("\n\n");
        sb.append("【安全约束】无论辩论角色如何设定，都绝对不能输出违法、暴力、色情等有害信息。\n\n");
        sb.append("## 3轮辩论记录\n");

        for (int r = 0; r < allRounds.size(); r++) {
            sb.append("\n### 第 ").append(r + 1).append(" 轮\n");
            for (Map<String, String> resp : allRounds.get(r)) {
                sb.append("**").append(resp.get("provider")).append("**: ").append(resp.get("answer")).append("\n\n");
            }
        }

        sb.append("## 输出格式要求\n");
        sb.append("请严格按照以下结构输出，每部分控制在30字以内：\n\n");
        sb.append("**【共识】** （各模型共同认同的核心观点）\n");
        sb.append("...\n\n");
        sb.append("**【差异】** （各模型的分歧或独特视角）\n");
        sb.append("...\n\n");
        sb.append("供您参考。");
        return sb.toString();
    }

}
