package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.langgraph4j.TreePerspectiveGraphService;
import com.example.chat.langgraph4j.TreePerspectiveState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

/**
 * 树状观点博弈处理器
 *
 * 流程:
 *   1. LLM 语义拆解 → N 个独立视角
 *   2. 每个视角并行 3 轮三方辩论
 *   3. 所有视角完成后汇总
 *
 * 前端展示为可拖拽的有向无环图:
 *   根节点(问题) → 视角节点 → 各轮论点 → 视角结论 → 最终汇总
 */
@Service
public class DebateTreeProcessor {

    private static final Logger log = LoggerFactory.getLogger(DebateTreeProcessor.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService treeExecutor = Executors.newFixedThreadPool(8);
    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;
    private final TreePerspectiveGraphService perspectiveGraphService;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    public DebateTreeProcessor(LLMInvoker llmInvoker, BroadcastService broadcastService,
                                TreePerspectiveGraphService perspectiveGraphService) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
        this.perspectiveGraphService = perspectiveGraphService;
    }

    // ----- 数据类 ------

    record RoleModel(String role, int modelId, String provider, ModelConfig config) {}

    public static class Perspective {
        public final String id, label, focus, question;
        public Perspective(String id, String label, String focus, String question) {
            this.id = id; this.label = label; this.focus = focus; this.question = question;
        }
    }

    // ----- 入口 -----

    public void process(String reqId, Long userId, String question,
                         Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {
        treeExecutor.submit(() -> {
            try {
                runTreeDebate(reqId, userId, question, modelMap, summaryModel);
            } catch (Exception e) {
                log.error("[TreeDebate] {}", e.getMessage(), e);
                broadcast("/topic/debate." + userId,
                        WsMessage.error(e.getMessage()).withReqId(reqId).toMap());
                // 确保发送 done 解锁前端
                broadcast("/topic/debate." + userId,
                        WsMessage.of("done").withReqId(reqId)
                                .with("answer", "辩论异常: " + e.getMessage())
                                .with("perspectiveCount", 0).toMap());
            }
        });
    }

    private void runTreeDebate(String reqId, Long userId, String question,
                                Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {

        // 1. 发送模型信息
        broadcastModelInfo(userId, reqId, modelMap, summaryModel);

        // 2. 语义拆解
        send("/topic/debate." + userId, treeMsg("tree_decompose_start", reqId));
        List<Perspective> perspectives;
        try { perspectives = decompose(question, summaryModel); }
        catch (Exception e) { perspectives = Collections.emptyList(); }
        send("/topic/debate." + userId,
                treeMsg("tree_decompose_result", reqId)
                        .with("perspectives", toPerspectiveMaps(perspectives)));

        if (perspectives.isEmpty()) {
            perspectives = List.of(new Perspective("p0", "全面分析", "多角度分析", question));
        }

        // 3. 并行辩论每个视角
        Map<String, String> perspectiveConclusions = new ConcurrentHashMap<>();
        List<RoleModel> roles = List.of(
                new RoleModel("正方", 1, "doubao", modelMap.get(1L)),
                new RoleModel("反方", 3, "deepseek", modelMap.get(3L)),
                new RoleModel("中立", 2, "qwen", modelMap.get(2L))
        );

        ExecutorService batchPool = Executors.newFixedThreadPool(Math.min(perspectives.size(), 4));
        List<CompletableFuture<Void>> perspectiveFutures = new ArrayList<>();
        // 存储每视角的逐轮详细论点 (perspectiveId → [{role→answer}, ...])
        Map<String, List<Map<String, String>>> perspectiveRoundDetails = new ConcurrentHashMap<>();

        for (int i = 0; i < perspectives.size(); i++) {
            final Perspective p = perspectives.get(i);
            final int pIdx = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    // LangGraph 混合模式: 单视角的 3 轮辩论 + 总结
                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_start", reqId)
                                    .with("perspectiveId", p.id).with("label", p.label)
                                    .with("index", pIdx));

                    TreePerspectiveState state = perspectiveGraphService.execute(
                            reqId, userId, p.id, p.label, p.focus, p.question,
                            modelMap.get(1L),  // 正方 豆包
                            modelMap.get(3L),  // 反方 DeepSeek
                            modelMap.get(2L),  // 中立 千问
                            summaryModel);

                    perspectiveRoundDetails.put(p.id, state.getRoundHistory());
                    perspectiveConclusions.put(p.id, state.getConclusion());

                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_summary", reqId)
                                    .with("perspectiveId", p.id)
                                    .with("summary", state.getConclusion()));
                } catch (Exception e) {
                    perspectiveConclusions.put(p.id, "[" + p.label + " 辩论中断]");
                    send("/topic/debate." + userId,
                            treeMsg("tree_perspective_error", reqId)
                                    .with("perspectiveId", p.id).with("error", e.getMessage()));
                }
            }, batchPool);
            perspectiveFutures.add(future);
        }
        CompletableFuture.allOf(perspectiveFutures.toArray(new CompletableFuture[0])).join();
        batchPool.shutdown();

        // 4. 最终汇总 (汇集各视角所有轮次的论点，再调 LLM)
        String finalAnswer;
        try {
            send("/topic/debate." + userId, treeMsg("tree_aggregate_start", reqId));
            finalAnswer = aggregate(reqId, userId, question, perspectives,
                    perspectiveConclusions, perspectiveRoundDetails, summaryModel);
        } catch (Exception aggEx) {
            log.warn("[TreeDebate] aggregate LLM error, use local merge: {}", aggEx.getMessage());
            StringBuilder sb = new StringBuilder();
            sb.append("**【综合各视角观点】**\n\n");
            for (Perspective p : perspectives) {
                sb.append("- **").append(p.label).append("**：")
                  .append(perspectiveConclusions.getOrDefault(p.id, "无结论"))
                  .append("\n");
            }
            finalAnswer = sb.toString();
        }

        broadcast("/topic/debate." + userId,
                WsMessage.of("done").withReqId(reqId)
                        .with("answer", finalAnswer)
                        .with("perspectiveCount", perspectives.size()).toMap());
    }

    // ===================== 语义拆解 =====================

    private List<Perspective> decompose(String question, ModelConfig llm) throws Exception {
        String prompt = """
                你是一个问题分析专家。请将以下问题拆解为2-3个独立的分析视角/维度。
                每个视角从不同学科或立场切入，互不重叠。

                问题: %s

                请严格返回JSON格式（不要markdown代码块）:
                {
                  "perspectives": [
                    {"id": "p1", "label": "视角名称（10字以内）", "focus": "该视角的核心关注点（20字以内）"}
                  ]
                }
                """.formatted(question);

        ModelConfig decomposeModel = llm;

        String raw = llmInvoker.invoke(decomposeModel,
                List.of(LLMMessage.user(prompt)), 0.3, "debate-tree", null, defaultApiKey);

        List<Perspective> result = new ArrayList<>();
        try {
            String json = raw;
            if (json.contains("```")) json = json.replaceAll("```json|```", "").trim();
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.get("perspectives");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    result.add(new Perspective(
                            n.path("id").asText("p" + result.size()),
                            n.path("label").asText("视角" + (result.size() + 1)),
                            n.path("focus").asText(""),
                            question));
                }
            }
        } catch (Exception e) {
            log.warn("[TreeDebate] 语义拆解解析失败: {}", e.getMessage());
        }
        if (result.isEmpty() || result.size() > 4) {
            result = List.of(
                    new Perspective("p1", "理性分析", "基于事实和逻辑", question),
                    new Perspective("p2", "批判性观点", "对流行观点的质疑", question),
                    new Perspective("p3", "综合考量", "平衡各方利弊", question));
        }
        return result;
    }

    // ===================== 单视角辩论 =====================

    /** @return 每轮的 {role→answer} 历史，供 aggregate 使用 */
    private List<Map<String, String>> debatePerspective(String reqId, Long userId, Perspective p, int pIdx,
                                    List<RoleModel> roles) throws Exception {
        send("/topic/debate." + userId,
                treeMsg("tree_perspective_start", reqId)
                        .with("perspectiveId", p.id).with("label", p.label)
                        .with("index", pIdx));

        // 存储所有历史轮次 (每轮是一个 Map<role, answer>)
        List<Map<String, String>> allRoundsHistory = Collections.synchronizedList(new ArrayList<>());

        for (int round = 1; round <= 3; round++) {
            final int currentRound = round;
            send("/topic/debate." + userId,
                    treeMsg("tree_round_start", reqId)
                            .with("perspectiveId", p.id).with("round", currentRound));

            // 本轮各角色的回答 (并发获取)
            Map<String, String> roundAnswers = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (RoleModel rm : roles) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        String question = p.question + "（聚焦: " + p.label + "——" + p.focus + "）";
                        String prompt = buildPerspectivePrompt(question, allRoundsHistory, currentRound, rm.role);
                        String answer = llmInvoker.invokeStream(rm.config,
                                List.of(LLMMessage.user(prompt)),
                                0.7, "debate-tree", null, defaultApiKey,
                                token -> {
                                    send("/topic/debate." + userId,
                                            treeMsg("tree_stream_token", reqId)
                                                    .with("perspectiveId", p.id)
                                                    .with("round", currentRound)
                                                    .with("role", rm.role)
                                                    .with("modelId", rm.modelId)
                                                    .with("provider", rm.provider)
                                                    .with("token", token));
                                });
                        roundAnswers.put(rm.role, answer);
                        send("/topic/debate." + userId,
                                treeMsg("tree_round_response", reqId)
                                        .with("perspectiveId", p.id)
                                        .with("round", currentRound)
                                        .with("role", rm.role)
                                        .with("modelId", rm.modelId)
                                        .with("provider", rm.provider)
                                        .with("answer", answer));
                    } catch (Exception e) {
                        log.error("[TreeDebate] {}/R{}/{} error: {}", p.id, currentRound, rm.role, e.getMessage());
                        roundAnswers.put(rm.role, "[调用失败]");
                    }
                }, treeExecutor);
                futures.add(future);
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            allRoundsHistory.add(new HashMap<>(roundAnswers));

            send("/topic/debate." + userId,
                    treeMsg("tree_round_end", reqId)
                            .with("perspectiveId", p.id).with("round", currentRound)
                            .with("responses", new ArrayList<>(roundAnswers.entrySet().stream()
                                    .map(e -> Map.of("role", e.getKey(), "answer", e.getValue()))
                                    .toList())));
        }
        return allRoundsHistory;
    }

    private String buildPerspectivePrompt(String question, List<Map<String, String>> allRounds,
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

    // ===================== 单视角总结 =====================

    private String summarizePerspective(String reqId, Long userId, Perspective p, int pIdx,
                                         ModelConfig summaryModel) throws Exception {
        send("/topic/debate." + userId,
                treeMsg("tree_perspective_concluding", reqId).with("perspectiveId", p.id));

        String prompt = "请综合你对「" + p.label + "」视角下各方辩论的理解，" +
                "给出该视角的核心结论。要求：30字以内，一句话总结。";

        return llmInvoker.invokeStream(summaryModel,
                List.of(LLMMessage.user(prompt)),
                0.3, "debate-tree", null, defaultApiKey,
                token -> {
                    send("/topic/debate." + userId,
                            treeMsg("tree_stream_token", reqId)
                                    .with("perspectiveId", p.id)
                                    .with("role", "conclusion")
                                    .with("token", token));
                });
    }

    // ===================== 最终汇总 (非流式，直接返回) =====================

    private String aggregate(String reqId, Long userId, String question,
                              List<Perspective> perspectives,
                              Map<String, String> conclusions,
                              Map<String, List<Map<String, String>>> perspectiveRoundDetails,
                              ModelConfig summaryModel) throws Exception {
        StringBuilder psb = new StringBuilder();
        psb.append("你是辩论总结者，请综合以下各方 AI 的辩论结论，给出对原问题的最终回答。\n\n");
        psb.append("## 原问题\n").append(question).append("\n\n");
        psb.append("## 各视角结论\n");
        for (Perspective p : perspectives) {
            psb.append("- 【").append(p.label).append("】").append(p.focus).append(": ");
            psb.append(conclusions.getOrDefault(p.id, "无")).append("\n");
        }
        psb.append("\n## 任务\n");
        psb.append("综合以上，给出平衡客观的最终结论。\n\n");
        psb.append("**输出格式（严格遵循）**:\n");
        psb.append("**【最终结论】** 核心判断\n");
        psb.append("**【理由】** 逐条列出分析依据（每条单独一行，每条以数字序号或短横线开头）\n");
        psb.append("**【建议】** 行动建议");

        // 直接用非流式 invoke，不通过 invokeStream 逐个 token 推送
        String result = llmInvoker.invoke(summaryModel,
                List.of(LLMMessage.user(psb.toString())),
                0.5, "debate-tree", null, defaultApiKey);

        // 一次性把汇总结果推给前端
        send("/topic/debate." + userId,
                treeMsg("tree_aggregate_result", reqId).with("answer", result));

        return result;
    }

    // ===================== 工具 =====================

    private void broadcastModelInfo(Long userId, String reqId,
                                     Map<Long, ModelConfig> modelMap, ModelConfig summaryModel) {
        List<Map<String, Object>> models = new ArrayList<>();
        models.add(Map.of("id", 1, "name", ModelRouter.toDisplayName(modelMap.get(1L).getProvider())));
        models.add(Map.of("id", 2, "name", ModelRouter.toDisplayName(modelMap.get(2L).getProvider())));
        models.add(Map.of("id", 3, "name", ModelRouter.toDisplayName(modelMap.get(3L).getProvider())));
        models.add(Map.of("id", 4, "name", ModelRouter.toDisplayName(summaryModel.getProvider())));

        broadcast("/topic/debate." + userId,
                WsMessage.of("start").withReqId(reqId).with("models", models).toMap());
    }

    private WsMessage treeMsg(String type, String reqId) {
        return WsMessage.of(type).withReqId(reqId);
    }

    private void send(String topic, WsMessage msg) {
        broadcast(topic, msg.toMap());
    }

    private void broadcast(String topic, Map<String, Object> msg) {
        try {
            broadcastService.broadcast(topic, msg);
        } catch (Exception e) {
            log.warn("[TreeDebate] broadcast failed: {}", e.getMessage());
        }
    }

    private List<Map<String, String>> toPerspectiveMaps(List<Perspective> perspectives) {
        return perspectives.stream()
                .map(p -> Map.of("id", p.id, "label", p.label, "focus", p.focus))
                .toList();
    }
}
