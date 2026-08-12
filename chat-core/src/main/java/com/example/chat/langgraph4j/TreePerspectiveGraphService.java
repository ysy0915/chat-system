package com.example.chat.langgraph4j;

import com.example.chat.client.LlmBundleClient;
import com.example.chat.dto.GraphStreamEventDto;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 树状辩论单视角子图服务（数据化实现，不依赖 langgraph4j）
 *
 * <p>每个视角的内部辩论表达为 {@link LangGraphRequest}（图数据），由 chat-llm 图引擎执行：
 *   incrementRound → debate(三方并行分支) → reflect(三方批判性反思) → shouldContinue
 *       ─┬─ true → incrementRound（循环）
 *       └─ false → summary（裁决式汇总） → END
 *
 * <p>SSE 流式事件映射为原有前端 WS 协议：
 *   tree_round_start / tree_stream_token(role+modelId) / tree_round_response /
 *   tree_round_end / tree_perspective_concluding / tree_stream_token(role=conclusion)。
 */
@Service
public class TreePerspectiveGraphService {

    private static final Logger log = LoggerFactory.getLogger(TreePerspectiveGraphService.class);

    @Autowired
    private LlmBundleClient llmBundleClient;
    @Autowired
    private BroadcastService broadcastService;

    @Value("${app.langgraph4j.debate.rounds:3}")
    private int maxRounds;

    /**
     * 执行单个视角的图辩论
     *
     * @return 包含 roundHistory / conclusion 的结果状态
     */
    public TreePerspectiveState execute(String reqId, Long userId,
                                        String perspectiveId, String perspectiveLabel,
                                        String perspectiveFocus, String question,
                                        ModelConfig proModel, ModelConfig conModel,
                                        ModelConfig neutralModel, ModelConfig summaryModel) {
        log.info("[TreeGraph] 视角{} ({}) graph start", perspectiveId, perspectiveLabel);

        LangGraphRequest graphReq = buildGraph(reqId, userId, perspectiveId, perspectiveLabel,
                perspectiveFocus, question, proModel, conModel, neutralModel, summaryModel);

        TreePerspectiveState result = new TreePerspectiveState();
        result.setPerspectiveId(perspectiveId);
        result.setPerspectiveLabel(perspectiveLabel);
        result.setPerspectiveFocus(perspectiveFocus);
        result.setQuestion(question);
        result.setUserId(userId != null ? userId : 0L);
        result.setReqId(reqId != null ? reqId : "");
        result.setMaxRounds(maxRounds);

        AtomicInteger round = new AtomicInteger(0);
        StringBuilder conclusionBuilder = new StringBuilder();
        List<String> m1 = new ArrayList<>();
        List<String> m2 = new ArrayList<>();
        List<String> m3 = new ArrayList<>();

        final Long uid = userId;
        final String rid = reqId;
        final String pid = perspectiveId;

        boolean success = llmBundleClient.graphStream(graphReq, event -> {
            switch (event.getType()) {
                case GraphStreamEventDto.TYPE_NODE_START -> {
                    if ("debate".equals(event.getNodeId())) {
                        round.incrementAndGet();
                        broadcast(uid, treeMsg("tree_round_start", rid)
                                .with("perspectiveId", pid).with("round", round.get()));
                    } else if ("summary".equals(event.getNodeId())) {
                        broadcast(uid, treeMsg("tree_perspective_concluding", rid)
                                .with("perspectiveId", pid));
                    }
                }
                case GraphStreamEventDto.TYPE_DELTA -> {
                    if ("debate".equals(event.getNodeId())) {
                        BranchInfo info = branchInfo(event.getBranchId());
                        if (info != null) {
                            broadcast(uid, treeMsg("tree_stream_token", rid)
                                    .with("perspectiveId", pid).with("round", round.get())
                                    .with("role", info.role).with("modelId", info.modelId)
                                    .with("provider", info.provider).with("token", event.getData()));
                        }
                    } else if ("summary".equals(event.getNodeId())) {
                        conclusionBuilder.append(event.getData());
                        broadcast(uid, treeMsg("tree_stream_token", rid)
                                .with("perspectiveId", pid).with("role", "conclusion")
                                .with("token", event.getData()));
                    }
                }
                case GraphStreamEventDto.TYPE_BRANCH_END -> {
                    if ("debate".equals(event.getNodeId()) && event.getBranchId() != null) {
                        String answer = event.getData() != null ? event.getData() : "";
                        BranchInfo info = branchInfo(event.getBranchId());
                        if (info == null) break;
                        switch (event.getBranchId()) {
                            case "pro" -> m1.add(answer);
                            case "neutral" -> m2.add(answer);
                            case "con" -> m3.add(answer);
                            default -> { }
                        }
                        broadcast(uid, treeMsg("tree_round_response", rid)
                                .with("perspectiveId", pid).with("round", round.get())
                                .with("role", info.role).with("modelId", info.modelId)
                                .with("provider", info.provider).with("answer", answer));
                    }
                }
                case GraphStreamEventDto.TYPE_NODE_END -> {
                    if ("debate".equals(event.getNodeId())) {
                        List<Map<String, String>> responses = new ArrayList<>();
                        if (event.getBranchId() == null) {
                            // node_end 无分支信息：跳过（响应在 branch_end 已推送）
                        }
                        broadcast(uid, treeMsg("tree_round_end", rid)
                                .with("perspectiveId", pid).with("round", round.get())
                                .with("responses", responses));
                    }
                }
                default -> { /* ignore */ }
            }
        });

        result.setCurrentRound(round.get());
        result.setModel1Answers(m1);
        result.setModel2Answers(m2);
        result.setModel3Answers(m3);
        result.setConclusion(success ? conclusionBuilder.toString() : null);

        log.info("[TreeGraph] 视角{} ({}) done, rounds={} m1={} m2={} m3={} success={}",
                perspectiveId, perspectiveLabel, round.get(), m1.size(), m2.size(), m3.size(), success);

        return result;
    }

    // ---- 图构建（数据化） ----

    private LangGraphRequest buildGraph(String reqId, Long userId,
                                        String perspectiveId, String perspectiveLabel,
                                        String perspectiveFocus, String question,
                                        ModelConfig proModel, ModelConfig conModel,
                                        ModelConfig neutralModel, ModelConfig summaryModel) {
        LangGraphRequest req = new LangGraphRequest();
        req.setProvider(conModel.provider);
        req.setModel(conModel.model);
        req.setEntryPoint("incrementRound");
        req.setMaxSteps(maxRounds * 4 + 2);

        String fullQuestion = question + "（聚焦: " + perspectiveLabel + "——" + perspectiveFocus + "）";

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("perspectiveId", perspectiveId != null ? perspectiveId : "");
        state.put("question", fullQuestion);
        state.put("userId", userId != null ? userId : 0L);
        state.put("reqId", reqId != null ? reqId : "");
        state.put("currentRound", 0);
        state.put("maxRounds", maxRounds);
        state.put("model1Answers", new ArrayList<String>());
        state.put("model2Answers", new ArrayList<String>());
        state.put("model3Answers", new ArrayList<String>());
        state.put("model1Reflections", new ArrayList<String>());
        state.put("model2Reflections", new ArrayList<String>());
        state.put("model3Reflections", new ArrayList<String>());
        req.setState(state);

        LangGraphRequest.GraphNode inc = node("incrementRound");
        inc.setNodeType("logic");
        inc.setLogic("increment:currentRound:1");

        LangGraphRequest.GraphNode debate = node("debate");
        debate.setBranches(List.of(
                branch("pro", proModel,
                        "你是一个AI辩论参与者，身份是「正方」。\n\n## 议题\n{{state.question}}\n\n" +
                        "## 此前讨论\n{{state.model1Answers}}\n\n## 任务\n给出你对这个视角的独立见解，观点明确、论据充分。50字以内。"),
                branch("con", conModel,
                        "你是一个AI辩论参与者，身份是「反方」。\n\n## 议题\n{{state.question}}\n\n" +
                        "## 此前讨论\n{{state.model3Answers}}\n\n## 任务\n阅读其他角色的观点后：1) 补充认同的观点 2) 反驳不认同的观点 3) 更新立场。50字以内。"),
                branch("neutral", neutralModel,
                        "你是一个AI辩论参与者，身份是「中立」。\n\n## 议题\n{{state.question}}\n\n" +
                        "## 此前讨论\n{{state.model2Answers}}\n\n## 任务\n给出对这个视角的独立见解，观点明确、论据充分。50字以内。")
        ));
        debate.getBranches().get(0).setSink("model1Answers");
        debate.getBranches().get(0).setSinkAppend(true);
        debate.getBranches().get(1).setSink("model3Answers");
        debate.getBranches().get(1).setSinkAppend(true);
        debate.getBranches().get(2).setSink("model2Answers");
        debate.getBranches().get(2).setSinkAppend(true);

        // reflect：三方批判性反思（审视对方观点后修正立场）
        LangGraphRequest.GraphNode reflect = node("reflect");
        reflect.setBranches(List.of(
                branch("pro", proModel,
                        "你是一个AI辩论参与者，身份是「正方」。「" + perspectiveLabel + "」视角，议题：{{state.question}}\n\n" +
                        "你的本轮观点：{{state.model1Answers[-1]}}\n" +
                        "反方本轮观点：{{state.model3Answers[-1]}}\n" +
                        "中立本轮观点：{{state.model2Answers[-1]}}\n\n" +
                        "请批判性反思（50字以内）：1) 我的观点哪一点被反驳得有道理？2) 修正后的立场是什么？"),
                branch("con", conModel,
                        "你是一个AI辩论参与者，身份是「反方」。「" + perspectiveLabel + "」视角，议题：{{state.question}}\n\n" +
                        "你的本轮观点：{{state.model3Answers[-1]}}\n" +
                        "正方本轮观点：{{state.model1Answers[-1]}}\n" +
                        "中立本轮观点：{{state.model2Answers[-1]}}\n\n" +
                        "请批判性反思（50字以内）：1) 我的观点哪一点被反驳得有道理？2) 修正后的立场是什么？"),
                branch("neutral", neutralModel,
                        "你是一个AI辩论参与者，身份是「中立」。「" + perspectiveLabel + "」视角，议题：{{state.question}}\n\n" +
                        "你的本轮分析：{{state.model2Answers[-1]}}\n" +
                        "正方本轮观点：{{state.model1Answers[-1]}}\n" +
                        "反方本轮观点：{{state.model3Answers[-1]}}\n\n" +
                        "请批判性反思（50字以内）：1) 双方哪方论证更严谨、漏洞是什么？2) 修正后的客观评价是什么？")
        ));
        reflect.getBranches().get(0).setSink("model1Reflections");
        reflect.getBranches().get(0).setSinkAppend(true);
        reflect.getBranches().get(1).setSink("model3Reflections");
        reflect.getBranches().get(1).setSinkAppend(true);
        reflect.getBranches().get(2).setSink("model2Reflections");
        reflect.getBranches().get(2).setSinkAppend(true);

        LangGraphRequest.GraphNode shouldContinue = node("shouldContinue");
        shouldContinue.setNodeType("logic");
        shouldContinue.setLogic("compare:{{state.currentRound}} < {{state.maxRounds}}");

        LangGraphRequest.GraphNode summary = node("summary");
        summary.setProvider(summaryModel.provider);
        summary.setModel(summaryModel.model);
        summary.setTemperature(0.3);
        summary.setSink("conclusion");
        summary.setTerminal(true);
        summary.setUserPrompt("请基于三方反思后的最终立场，综合你对「" + perspectiveLabel + "」视角下各方辩论的理解，" +
                "给出该视角的核心结论。要求：一句话总结（30字以内），并指出各方论证的强弱点（20字以内）。");

        req.setNodes(List.of(inc, debate, reflect, shouldContinue, summary));
        req.setEdges(List.of(
                edge("incrementRound", "debate"),
                edge("debate", "reflect"),
                edge("reflect", "shouldContinue"),
                condEdge("shouldContinue", "incrementRound", "contains({{output}}, 'true')"),
                defaultEdge("shouldContinue", "summary")
        ));
        return req;
    }

    // ---- 工具 ----

    private record BranchInfo(String role, int modelId, String provider) {}

    private BranchInfo branchInfo(String branchId) {
        if (branchId == null) return null;
        return switch (branchId) {
            case "pro" -> new BranchInfo("正方", 1, "doubao");
            case "neutral" -> new BranchInfo("中立", 2, "qwen");
            case "con" -> new BranchInfo("反方", 3, "deepseek");
            default -> null;
        };
    }

    private LangGraphRequest.GraphNode node(String id) {
        LangGraphRequest.GraphNode n = new LangGraphRequest.GraphNode();
        n.setId(id);
        n.setLabel(id);
        return n;
    }

    private LangGraphRequest.GraphBranch branch(String id, ModelConfig model, String userPrompt) {
        LangGraphRequest.GraphBranch b = new LangGraphRequest.GraphBranch();
        b.setId(id);
        b.setLabel(id);
        b.setProvider(model.provider);
        b.setModel(model.model);
        b.setTemperature(0.7);
        b.setUserPrompt(userPrompt);
        return b;
    }

    private LangGraphRequest.GraphEdge edge(String from, String to) {
        LangGraphRequest.GraphEdge e = new LangGraphRequest.GraphEdge();
        e.setFrom(from);
        e.setTo(to);
        return e;
    }

    private LangGraphRequest.GraphEdge condEdge(String from, String to, String condition) {
        LangGraphRequest.GraphEdge e = edge(from, to);
        e.setCondition(condition);
        return e;
    }

    private LangGraphRequest.GraphEdge defaultEdge(String from, String to) {
        LangGraphRequest.GraphEdge e = edge(from, to);
        e.setDefaultRoute(true);
        return e;
    }

    private WsMessage treeMsg(String type, String reqId) {
        return WsMessage.of(type).withReqId(reqId);
    }

    private void broadcast(Long userId, WsMessage msg) {
        try {
            if (userId != null && userId != 0) {
                broadcastService.broadcast("/topic/debate." + userId, msg.toMap());
            }
        } catch (Exception e) {
            log.warn("[TreeGraph] broadcast failed: {}", e.getMessage());
        }
    }
}
