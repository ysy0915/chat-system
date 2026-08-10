package com.example.chat.langgraph4j;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * 树状辩论单视角的 LangGraph 节点
 *
 * 3 个节点:
 *   debateNode    — 三方并行辩论（正方/反方/中立）
 *   shouldContinue — 轮次判断
 *   summaryNode   — 视角总结
 */
public class TreePerspectiveNodes {

    private static final Logger log = LoggerFactory.getLogger(TreePerspectiveNodes.class);

    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;
    private final ModelConfig proModel;      // 正方 豆包
    private final ModelConfig conModel;      // 反方 DeepSeek
    private final ModelConfig neutralModel;  // 中立 千问
    private final ModelConfig summaryModel;  // 总结模型
    private final String defaultApiKey;
    private final ExecutorService executor;

    public TreePerspectiveNodes(LLMInvoker llmInvoker, BroadcastService broadcastService,
                                 ModelConfig proModel, ModelConfig conModel,
                                 ModelConfig neutralModel, ModelConfig summaryModel,
                                 String defaultApiKey, ExecutorService executor) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.proModel = proModel;
        this.conModel = conModel;
        this.neutralModel = neutralModel;
        this.summaryModel = summaryModel;
        this.defaultApiKey = defaultApiKey;
        this.executor = executor;
    }

    /**
     * 节点1：三方并行辩论
     */
    public NodeAction<TreePerspectiveState> debateNode() {
        return state -> {
            int round = state.getCurrentRound() + 1;
            String pid = state.getPerspectiveId();
            String label = state.getPerspectiveLabel();
            String focus = state.getPerspectiveFocus();
            String question = state.getQuestion();
            Long userId = state.getUserId();
            String reqId = state.getReqId();
            List<Map<String, String>> history = state.getRoundHistory();

            log.info("[TreeGraph] 视角{} 第{}轮 debate start", pid, round);

            // 推送轮次开始
            broadcast(userId, treeMsg("tree_round_start", reqId)
                    .with("perspectiveId", pid).with("round", round).toMap());

            Map<String, String> roundAnswers = new ConcurrentHashMap<>();

            // 正方 (豆包)
            String proRole = "正方", conRole = "反方", neutralRole = "中立";
            String fullQuestion = question + "（聚焦: " + label + "——" + focus + "）";

            CompletableFuture<Void> proF = CompletableFuture.runAsync(() -> {
                try {
                    String prompt = buildPrompt(fullQuestion, history, round, proRole);
                    String answer = llmInvoker.invokeStream(proModel,
                            List.of(LLMMessage.user(prompt)), 0.7, "debate-tree", null, defaultApiKey,
                            token -> broadcast(userId, treeMsg("tree_stream_token", reqId)
                                    .with("perspectiveId", pid).with("round", round)
                                    .with("role", proRole).with("modelId", 1)
                                    .with("provider", "doubao").with("token", token).toMap()));
                    roundAnswers.put(proRole, answer);
                    broadcast(userId, treeMsg("tree_round_response", reqId)
                            .with("perspectiveId", pid).with("round", round)
                            .with("role", proRole).with("modelId", 1)
                            .with("provider", "doubao").with("answer", answer).toMap());
                } catch (Exception e) {
                    log.error("[TreeGraph] {}/R{}/正方 error: {}", pid, round, e.getMessage());
                    roundAnswers.put(proRole, "[调用失败]");
                }
            }, executor);

            // 反方 (DeepSeek)
            CompletableFuture<Void> conF = CompletableFuture.runAsync(() -> {
                try {
                    String prompt = buildPrompt(fullQuestion, history, round, conRole);
                    String answer = llmInvoker.invokeStream(conModel,
                            List.of(LLMMessage.user(prompt)), 0.7, "debate-tree", null, defaultApiKey,
                            token -> broadcast(userId, treeMsg("tree_stream_token", reqId)
                                    .with("perspectiveId", pid).with("round", round)
                                    .with("role", conRole).with("modelId", 3)
                                    .with("provider", "deepseek").with("token", token).toMap()));
                    roundAnswers.put(conRole, answer);
                    broadcast(userId, treeMsg("tree_round_response", reqId)
                            .with("perspectiveId", pid).with("round", round)
                            .with("role", conRole).with("modelId", 3)
                            .with("provider", "deepseek").with("answer", answer).toMap());
                } catch (Exception e) {
                    log.error("[TreeGraph] {}/R{}/反方 error: {}", pid, round, e.getMessage());
                    roundAnswers.put(conRole, "[调用失败]");
                }
            }, executor);

            // 中立 (千问)
            CompletableFuture<Void> neuF = CompletableFuture.runAsync(() -> {
                try {
                    String prompt = buildPrompt(fullQuestion, history, round, neutralRole);
                    String answer = llmInvoker.invokeStream(neutralModel,
                            List.of(LLMMessage.user(prompt)), 0.7, "debate-tree", null, defaultApiKey,
                            token -> broadcast(userId, treeMsg("tree_stream_token", reqId)
                                    .with("perspectiveId", pid).with("round", round)
                                    .with("role", neutralRole).with("modelId", 2)
                                    .with("provider", "qwen").with("token", token).toMap()));
                    roundAnswers.put(neutralRole, answer);
                    broadcast(userId, treeMsg("tree_round_response", reqId)
                            .with("perspectiveId", pid).with("round", round)
                            .with("role", neutralRole).with("modelId", 2)
                            .with("provider", "qwen").with("answer", answer).toMap());
                } catch (Exception e) {
                    log.error("[TreeGraph] {}/R{}/中立 error: {}", pid, round, e.getMessage());
                    roundAnswers.put(neutralRole, "[调用失败]");
                }
            }, executor);

            CompletableFuture.allOf(proF, conF, neuF).join();

            // 更新轮次历史
            List<Map<String, String>> newHistory = new ArrayList<>(history);
            newHistory.add(new HashMap<>(roundAnswers));

            // 更新各方答案列表
            List<String> m1 = new ArrayList<>(state.getModel1Answers());
            List<String> m2 = new ArrayList<>(state.getModel2Answers());
            List<String> m3 = new ArrayList<>(state.getModel3Answers());
            m1.add(roundAnswers.getOrDefault(proRole, ""));
            m2.add(roundAnswers.getOrDefault(neutralRole, ""));
            m3.add(roundAnswers.getOrDefault(conRole, ""));

            // 推送轮次结束
            broadcast(userId, treeMsg("tree_round_end", reqId)
                    .with("perspectiveId", pid).with("round", round)
                    .with("responses", new ArrayList<>(roundAnswers.entrySet().stream()
                            .map(e -> Map.of("role", e.getKey(), "answer", e.getValue())).toList()))
                    .toMap());

            Map<String, Object> update = new HashMap<>();
            update.put(TreePerspectiveState.ROUND_HISTORY, newHistory);
            update.put(TreePerspectiveState.MODEL_1_ANSWERS, m1);
            update.put(TreePerspectiveState.MODEL_2_ANSWERS, m2);
            update.put(TreePerspectiveState.MODEL_3_ANSWERS, m3);
            update.put(TreePerspectiveState.CURRENT_ROUND, round);
            return update;
        };
    }

    /**
     * 节点2：条件判断
     */
    public NodeAction<TreePerspectiveState> shouldContinue() {
        return state -> {
            boolean more = state.getCurrentRound() < state.getMaxRounds();
            return Map.of(TreePerspectiveState.NEXT, more ? "debate" : "summary");
        };
    }

    /**
     * 节点3：视角总结
     */
    public NodeAction<TreePerspectiveState> summaryNode() {
        return state -> {
            String pid = state.getPerspectiveId();
            String label = state.getPerspectiveLabel();
            Long userId = state.getUserId();
            String reqId = state.getReqId();

            log.info("[TreeGraph] 视角{} summarizing", pid);

            // 推送总结开始
            broadcast(userId, treeMsg("tree_perspective_concluding", reqId)
                    .with("perspectiveId", pid).toMap());

            String prompt = "请综合你对「" + label + "」视角下各方辩论的理解，" +
                    "给出该视角的核心结论。要求：30字以内，一句话总结。";

            String conclusion;
            try {
                conclusion = llmInvoker.invokeStream(summaryModel,
                        List.of(LLMMessage.user(prompt)),
                        0.3, "debate-tree", null, defaultApiKey,
                        token -> broadcast(userId, treeMsg("tree_stream_token", reqId)
                                .with("perspectiveId", pid).with("role", "conclusion")
                                .with("token", token).toMap()));
            } catch (Exception e) {
                log.error("[TreeGraph] {} summary error: {}", pid, e.getMessage());
                conclusion = "[" + label + " 总结失败]";
            }

            return Map.of(TreePerspectiveState.CONCLUSION, conclusion);
        };
    }

    // ---- 工具 ----

    private String buildPrompt(String question, List<Map<String, String>> allRounds,
                                int round, String role) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个AI辩论参与者，身份是「").append(role).append("」。\n\n");
        sb.append("## 议题\n").append(question).append("\n\n");

        if (round > 1 && !allRounds.isEmpty()) {
            sb.append("## 此前讨论\n");
            for (int r = 0; r < allRounds.size(); r++) {
                Map<String, String> roundData = allRounds.get(r);
                for (Map.Entry<String, String> entry : roundData.entrySet()) {
                    sb.append("**").append(entry.getKey()).append("**: ")
                            .append(entry.getValue()).append("\n\n");
                }
            }
        }

        sb.append("## 第").append(round).append("轮任务\n");
        if (round == 1) {
            sb.append("给出你对这个视角的独立见解，观点明确、论据充分。50字以内。\n");
        } else {
            sb.append("阅读其他角色的观点后：1) 补充认同的观点 2) 反驳不认同的观点 3) 更新立场。50字以内。\n");
        }
        return sb.toString();
    }

    private WsMessage treeMsg(String type, String reqId) {
        return WsMessage.of(type).withReqId(reqId);
    }

    private void broadcast(Long userId, Map<String, Object> msg) {
        try {
            broadcastService.broadcast("/topic/debate." + userId, msg);
        } catch (Exception e) {
            log.warn("[TreeGraph] broadcast failed: {}", e.getMessage());
        }
    }
}
