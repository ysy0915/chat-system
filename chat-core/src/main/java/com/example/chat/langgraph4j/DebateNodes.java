package com.example.chat.langgraph4j;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 辩论图的节点动作（NodeAction）
 *
 * 每个节点接收 DebateState，返回状态更新 Map
 *
 * 辩论图结构：
 *   开始 → 正方发言 → 反方发言 → 中立方发言 → 条件判断
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
    private final ExecutorService parallelExecutor;

    public DebateNodes(LLMInvoker llmInvoker, BroadcastService broadcastService,
                       ModelConfig proModel, ModelConfig conModel, ModelConfig summaryModel,
                       String defaultApiKey, ExecutorService parallelExecutor) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.proModel = proModel;
        this.conModel = conModel;
        this.summaryModel = summaryModel;
        this.defaultApiKey = defaultApiKey;
        this.parallelExecutor = parallelExecutor;
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
     * 节点1：三方并行辩论（正方+反方+中立方同时发言）
     */
    public NodeAction<DebateState> debateNode() {
        return state -> {
            int round = state.getCurrentRound() + 1;
            String topic = state.getTopic();
            List<String> prevPro = state.getProArguments();
            List<String> prevCon = state.getConArguments();
            List<String> prevNeutral = state.getNeutralArguments();

            final Long userId = state.getUserId();
            final String reqId = state.getReqId();

            log.info("[DebateGraph] 三方并行辩论 round={}", round);

            // 推送轮次开始
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_start").withReqId(reqId).with("round", round).toMap());
            }

            // 正方 prompt
            String proPrompt = "你是辩论的正方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevCon.isEmpty() ? "" : "\n反方上一轮观点：" + prevCon.get(prevCon.size() - 1) + "\n请针对反方观点进行反驳。");

            // 反方 prompt
            String conPrompt = "你是辩论的反方。话题：「" + topic + "」\n" +
                    "请用 100 字以内阐述你的观点。" +
                    (prevPro.isEmpty() ? "" : "\n正方上一轮观点：" + prevPro.get(prevPro.size() - 1) + "\n请针对正方观点进行反驳。");

            // 中立方 prompt
            String neutralPrompt = "你是辩论的中立方评论员。话题：「" + topic + "」\n" +
                    "请用 100 字以内给出你的客观分析。" +
                    (!prevPro.isEmpty() ? "\n正方上一轮观点：" + prevPro.get(prevPro.size() - 1) : "") +
                    (!prevCon.isEmpty() ? "\n反方上一轮观点：" + prevCon.get(prevCon.size() - 1) : "") +
                    "\n请综合双方观点，给出中立客观的评价或补充视角。";

            // 三方并行流式调用
            final StringBuilder proBuilder = new StringBuilder();
            final StringBuilder conBuilder = new StringBuilder();
            final StringBuilder neutralBuilder = new StringBuilder();

            CompletableFuture<Void> proFuture = CompletableFuture.runAsync(() -> {
                try {
                    llmInvoker.invokeStream(proModel,
                        List.of(LLMMessage.user( proPrompt)),
                        0.7, "debate:pro", null, defaultApiKey,
                        token -> {
                            proBuilder.append(token);
                            if (userId != 0) {
                                broadcastService.broadcast("/topic/debate." + userId,
                                    WsMessage.streamToken(token)
                                        .withReqId(reqId).with("model_id", 1).toMap());
                            }
                        });
                } catch (Exception e) {
                    log.error("[DebateGraph] 正方调用失败 round={}: {}", round, e.getMessage());
                    proBuilder.append("[").append(providerName(proModel)).append(" 调用失败]");
                }
            }, parallelExecutor);

            CompletableFuture<Void> conFuture = CompletableFuture.runAsync(() -> {
                try {
                    llmInvoker.invokeStream(conModel,
                        List.of(LLMMessage.user( conPrompt)),
                        0.7, "debate:con", null, defaultApiKey,
                        token -> {
                            conBuilder.append(token);
                            if (userId != 0) {
                                broadcastService.broadcast("/topic/debate." + userId,
                                    WsMessage.streamToken(token)
                                        .withReqId(reqId).with("model_id", 3).toMap());
                            }
                        });
                } catch (Exception e) {
                    log.error("[DebateGraph] 反方调用失败 round={}: {}", round, e.getMessage());
                    conBuilder.append("[").append(providerName(conModel)).append(" 调用失败]");
                }
            }, parallelExecutor);

            CompletableFuture<Void> neutralFuture = CompletableFuture.runAsync(() -> {
                try {
                    llmInvoker.invokeStream(summaryModel,
                        List.of(LLMMessage.user( neutralPrompt)),
                        0.7, "debate:neutral", null, defaultApiKey,
                        token -> {
                            neutralBuilder.append(token);
                            if (userId != 0) {
                                broadcastService.broadcast("/topic/debate." + userId,
                                    WsMessage.streamToken(token)
                                        .withReqId(reqId).with("model_id", 2).toMap());
                            }
                        });
                } catch (Exception e) {
                    log.error("[DebateGraph] 中立方调用失败 round={}: {}", round, e.getMessage());
                    neutralBuilder.append("[").append(providerName(summaryModel)).append(" 调用失败]");
                }
            }, parallelExecutor);

            // 等待三方全部完成
            CompletableFuture.allOf(proFuture, conFuture, neutralFuture).join();

            String proAnswer = proBuilder.toString();
            String conAnswer = conBuilder.toString();
            String neutralAnswer = neutralBuilder.toString();

            // 推送完整回答（标记各方流式结束）
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_response").withReqId(reqId)
                        .with("round", round).with("model_id", 1)
                        .with("provider", providerName(proModel)).with("answer", proAnswer).toMap());
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_response").withReqId(reqId)
                        .with("round", round).with("model_id", 3)
                        .with("provider", providerName(conModel)).with("answer", conAnswer).toMap());
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_response").withReqId(reqId)
                        .with("round", round).with("model_id", 2)
                        .with("provider", providerName(summaryModel)).with("answer", neutralAnswer).toMap());
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("round_end").withReqId(reqId).with("round", round).toMap());
            }

            List<String> newPro = new ArrayList<>(prevPro);
            newPro.add(proAnswer);
            List<String> newCon = new ArrayList<>(prevCon);
            newCon.add(conAnswer);
            List<String> newNeutral = new ArrayList<>(prevNeutral);
            newNeutral.add(neutralAnswer);

            Map<String, Object> update = new HashMap<>();
            update.put(DebateState.PRO_ARGUMENTS, newPro);
            update.put(DebateState.CON_ARGUMENTS, newCon);
            update.put(DebateState.NEUTRAL_ARGUMENTS, newNeutral);
            update.put(DebateState.CURRENT_ROUND, round);
            return update;
        };
    }

    /**
     * 节点3：条件判断——是否需要更多轮次
     */
    public NodeAction<DebateState> shouldContinue() {
        return state -> {
            boolean more = state.getCurrentRound() < state.getMaxRounds();
            log.info("[DebateGraph] 条件判断 round={}/{} continue={}", state.getCurrentRound(), state.getMaxRounds(), more);
            return Map.of(DebateState.NEXT, more ? "debate" : "summary");
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
            List<String> neutralArgs = state.getNeutralArguments();

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
            prompt.append("中立方观点：\n");
            for (int i = 0; i < neutralArgs.size(); i++) {
                prompt.append("  第").append(i + 1).append("轮：").append(neutralArgs.get(i)).append("\n");
            }
            prompt.append("\n请按照以下格式汇总三方观点，每部分换行，每部分 50 字以内：\n");
            prompt.append("【正方强调】（正方核心观点）\n...\n\n");
            prompt.append("【反方强调】（反方核心观点）\n...\n\n");
            prompt.append("【中立强调】（中立方客观评价）\n...\n\n");
            prompt.append("【共识结论】（三方共同认同的结论）\n...");

            log.info("[DebateGraph] 汇总共识 proRounds={} conRounds={}", proArgs.size(), conArgs.size());

            final Long userId = state.getUserId();
            final String reqId = state.getReqId();

            // 推送整合阶段开始
            if (userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId,
                    WsMessage.of("synthesizing").withReqId(reqId)
                        .with("synthesizer", providerName(summaryModel)).toMap());
            }

            // 流式调用，实时推送整合 token
            final StringBuilder summaryBuilder = new StringBuilder();
            try {
                llmInvoker.invokeStream(summaryModel,
                    List.of(LLMMessage.user( prompt.toString())),
                    0.5, "debate:summary", null, defaultApiKey,
                    token -> {
                        summaryBuilder.append(token);
                        if (userId != 0) {
                            broadcastService.broadcast("/topic/debate." + userId,
                                WsMessage.streamToken(token)
                                    .withReqId(reqId).with("model_id", 4).toMap());
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
                    WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", summary).toMap());
            }

            return Map.of(DebateState.SUMMARY, summary);
        };
    }
}
