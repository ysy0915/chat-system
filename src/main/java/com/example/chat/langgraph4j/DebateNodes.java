package com.example.chat.langgraph4j;

import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 辩论图的节点动作（NodeAction）
 *
 * 每个节点接收 DebateState，返回状态更新 Map
 *
 * 辩论图结构：
 *   开始 → 正方发言 → 反方发言 → 条件判断
 *                                    ├─ 继续 → 正方发言（循环）
 *                                    └─ 结束 → 汇总共识 → 结束
 */
public class DebateNodes {

    private static final Logger log = LoggerFactory.getLogger(DebateNodes.class);

    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;
    private final ModelConfig proModel;
    private final ModelConfig conModel;
    private final ModelConfig summaryModel;
    private final String defaultApiKey;

    public DebateNodes(LLMInvoker llmInvoker, BroadcastService broadcastService,
                       ModelConfig proModel, ModelConfig conModel, ModelConfig summaryModel,
                       String defaultApiKey) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.proModel = proModel;
        this.conModel = conModel;
        this.summaryModel = summaryModel;
        this.defaultApiKey = defaultApiKey;
    }

    private static String providerName(ModelConfig config) {
        if (config == null || config.provider == null) return "未知";
        return switch (config.provider.toLowerCase()) {
            case "doubao" -> "豆包";
            case "qwen" -> "千问";
            case "deepseek" -> "DeepSeek";
            case "zhipu" -> "智谱";
            default -> config.provider;
        };
    }

    /**
     * 节点1：正方发言
     */
    public NodeAction<DebateState> proNode() {
        return state -> {
            int round = state.getCurrentRound() + 1;
            String topic = state.getTopic();
            List<String> prevCon = state.getConArguments();

            String prompt = "你是辩论的正方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevCon.isEmpty() ? "" : "\n反方上一轮观点：" + prevCon.get(prevCon.size() - 1) + "\n请针对反方观点进行反驳。");

            log.info("[DebateGraph] 正方发言 round={}", round);

            // 推送轮次开始 + 思考状态
            if (state.getUserId() != 0) {
                broadcastService.broadcast("/topic/debate." + state.getUserId(),
                    Map.of("type", "round_start", "req_id", state.getReqId(), "round", round));
            }

            // 流式调用，实时推送 token
            final StringBuilder answerBuilder = new StringBuilder();
            final Long userId = state.getUserId();
            final String reqId = state.getReqId();
            try {
                llmInvoker.invokeStream(proModel,
                    List.of(Map.of("role", "user", "content", prompt)),
                    0.7, "debate:pro", null, defaultApiKey,
                    token -> {
                        answerBuilder.append(token);
                        if (userId != 0) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                Map.of("type", "stream_token", "req_id", reqId,
                                       "model_id", 1, "token", token));
                        }
                    });
            } catch (Exception e) {
                log.error("[DebateGraph] 正方调用失败 round={}: {}", round, e.getMessage());
                answerBuilder.append("[").append(providerName(proModel)).append(" 调用失败]");
            }

            String answer = answerBuilder.toString();

            // 推送完整回答（标记流式结束）
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "round_response", "req_id", reqId,
                           "round", round, "model_id", 1,
                           "provider", providerName(proModel), "answer", answer));
            }

            List<String> newPro = new ArrayList<>(state.getProArguments());
            newPro.add(answer);

            Map<String, Object> update = new HashMap<>();
            update.put(DebateState.PRO_ARGUMENTS, newPro);
            update.put(DebateState.CURRENT_ROUND, round);
            return update;
        };
    }

    /**
     * 节点2：反方发言
     */
    public NodeAction<DebateState> conNode() {
        return state -> {
            int round = state.getCurrentRound();
            String topic = state.getTopic();
            List<String> prevPro = state.getProArguments();

            String prompt = "你是辩论的反方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevPro.isEmpty() ? "" : "\n正方上一轮观点：" + prevPro.get(prevPro.size() - 1) + "\n请针对正方观点进行反驳。");

            log.info("[DebateGraph] 反方发言 round={}", round);

            final Long userId = state.getUserId();
            final String reqId = state.getReqId();

            // 流式调用，实时推送 token
            final StringBuilder answerBuilder = new StringBuilder();
            try {
                llmInvoker.invokeStream(conModel,
                    List.of(Map.of("role", "user", "content", prompt)),
                    0.7, "debate:con", null, defaultApiKey,
                    token -> {
                        answerBuilder.append(token);
                        if (userId != 0) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                Map.of("type", "stream_token", "req_id", reqId,
                                       "model_id", 3, "token", token));
                        }
                    });
            } catch (Exception e) {
                log.error("[DebateGraph] 反方调用失败 round={}: {}", round, e.getMessage());
                answerBuilder.append("[").append(providerName(conModel)).append(" 调用失败]");
            }

            String answer = answerBuilder.toString();

            // 推送完整回答
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "round_response", "req_id", reqId,
                           "round", round, "model_id", 3,
                           "provider", providerName(conModel), "answer", answer));
            }

            List<String> newCon = new ArrayList<>(state.getConArguments());
            newCon.add(answer);

            return Map.of(DebateState.CON_ARGUMENTS, newCon);
        };
    }

    /**
     * 节点3：条件判断——是否需要更多轮次
     */
    public NodeAction<DebateState> shouldContinue() {
        return state -> {
            boolean more = state.getCurrentRound() < state.getMaxRounds();
            log.info("[DebateGraph] 条件判断 round={}/{} continue={}", state.getCurrentRound(), state.getMaxRounds(), more);
            return Map.of(DebateState.NEXT, more ? "pro" : "summary");
        };
    }

    /**
     * 节点4：汇总共识
     */
    public NodeAction<DebateState> summaryNode() {
        return state -> {
            String topic = state.getTopic();
            List<String> proArgs = state.getProArguments();
            List<String> conArgs = state.getConArguments();

            StringBuilder prompt = new StringBuilder();
            prompt.append("你是辩论主持人。话题：「").append(topic).append("」\n");
            prompt.append("正方观点：\n");
            for (int i = 0; i < proArgs.size(); i++) {
                prompt.append("  第").append(i + 1).append("轮：").append(proArgs.get(i)).append("\n");
            }
            prompt.append("反方观点：\n");
            for (int i = 0; i < conArgs.size(); i++) {
                prompt.append("  第").append(i + 1).append("轮：").append(conArgs.get(i)).append("\n");
            }
            prompt.append("\n请汇总双方观点，找出共识和差异，给出 200 字以内的总结。");

            log.info("[DebateGraph] 汇总共识 proRounds={} conRounds={}", proArgs.size(), conArgs.size());

            final Long userId = state.getUserId();
            final String reqId = state.getReqId();

            // 推送整合阶段开始
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "synthesizing", "req_id", reqId,
                           "synthesizer", providerName(summaryModel)));
            }

            // 流式调用，实时推送整合 token
            final StringBuilder summaryBuilder = new StringBuilder();
            try {
                llmInvoker.invokeStream(summaryModel,
                    List.of(Map.of("role", "user", "content", prompt.toString())),
                    0.5, "debate:summary", null, defaultApiKey,
                    token -> {
                        summaryBuilder.append(token);
                        if (userId != 0) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                Map.of("type", "stream_token", "req_id", reqId,
                                       "model_id", 4, "token", token));
                        }
                    });
            } catch (Exception e) {
                log.error("[DebateGraph] 汇总调用失败: {}", e.getMessage());
                summaryBuilder.append("[整合失败]");
            }

            String summary = summaryBuilder.toString();

            // 推送完成
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    Map.of("type", "done", "req_id", reqId, "answer", summary));
            }

            return Map.of(DebateState.SUMMARY, summary);
        };
    }
}
