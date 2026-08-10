package com.example.chat.langgraph4j;

import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 树状辩论单视角子图服务（LangGraph 混合模式）
 *
 * 每个视角的内部辩论用 LangGraph 做图式编排：
 *   START → debate → shouldContinue ─┬─ "debate" (循环)
 *                                    └─ "summary" → END
 *
 * 上层 decompose / aggregate 仍由 DebateTreeProcessor 用 Java 方法完成。
 *
 * 使用方式:
 *   TreePerspectiveState result = graphService.execute(reqId, userId,
 *       perspectiveLabel, perspectiveFocus, question, proModel, conModel, neutralModel, summaryModel);
 *   // result.getRoundHistory() → 逐轮 {角色→答案}
 *   // result.getConclusion()   → 视角总结
 */
@Service
public class TreePerspectiveGraphService {

    private static final Logger log = LoggerFactory.getLogger(TreePerspectiveGraphService.class);

    private final LLMInvoker llmInvoker;
    private final BroadcastService broadcastService;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @Value("${app.langgraph4j.debate.rounds:3}")
    private int maxRounds;

    // 每个视角单线程执行（视角间已在上层并行）
    private final ExecutorService perPerspectiveExecutor = Executors.newFixedThreadPool(4);

    public TreePerspectiveGraphService(LLMInvoker llmInvoker, BroadcastService broadcastService) {
        this.llmInvoker = llmInvoker;
        this.broadcastService = broadcastService;
    }

    /**
     * 执行单个视角的 LangGraph 辩论
     *
     * @return 包含 roundHistory / conclusion 的结果状态
     */
    public TreePerspectiveState execute(String reqId, Long userId,
                                         String perspectiveId, String perspectiveLabel,
                                         String perspectiveFocus, String question,
                                         ModelConfig proModel, ModelConfig conModel,
                                         ModelConfig neutralModel, ModelConfig summaryModel)
            throws Exception {

        log.info("[TreeGraph] 视角{} ({}) graph start", perspectiveId, perspectiveLabel);

        TreePerspectiveNodes nodes = new TreePerspectiveNodes(
                llmInvoker, broadcastService, proModel, conModel, neutralModel,
                summaryModel, defaultApiKey, perPerspectiveExecutor);

        StateGraph<TreePerspectiveState> graph = buildGraph(nodes);
        CompiledGraph<TreePerspectiveState> compiled = graph.compile();

        Map<String, Object> initData = new HashMap<>();
        initData.put(TreePerspectiveState.PERSPECTIVE_ID, perspectiveId);
        initData.put(TreePerspectiveState.PERSPECTIVE_LABEL, perspectiveLabel);
        initData.put(TreePerspectiveState.PERSPECTIVE_FOCUS, perspectiveFocus);
        initData.put(TreePerspectiveState.QUESTION, question);
        initData.put(TreePerspectiveState.USER_ID, userId != null ? userId : 0L);
        initData.put(TreePerspectiveState.REQ_ID, reqId != null ? reqId : "");
        initData.put(TreePerspectiveState.CURRENT_ROUND, 0);
        initData.put(TreePerspectiveState.MAX_ROUNDS, maxRounds);
        initData.put(TreePerspectiveState.ROUND_HISTORY, Collections.synchronizedList(new ArrayList<>()));
        initData.put(TreePerspectiveState.MODEL_1_ANSWERS, Collections.synchronizedList(new ArrayList<>()));
        initData.put(TreePerspectiveState.MODEL_2_ANSWERS, Collections.synchronizedList(new ArrayList<>()));
        initData.put(TreePerspectiveState.MODEL_3_ANSWERS, Collections.synchronizedList(new ArrayList<>()));
        initData.put(TreePerspectiveState.CONCLUSION, "");

        var result = compiled.invoke(initData);
        TreePerspectiveState finalState = result.orElseThrow(
                () -> new RuntimeException("LangGraph perspective " + perspectiveId + " returned empty"));

        log.info("[TreeGraph] 视角{} ({}) done, rounds={} conclusionLen={}",
                perspectiveId, perspectiveLabel,
                finalState.getCurrentRound(),
                finalState.getConclusion() != null ? finalState.getConclusion().length() : 0);

        return finalState;
    }

    // ---- 图构建 ----

    private StateGraph<TreePerspectiveState> buildGraph(TreePerspectiveNodes nodes) throws Exception {
        StateGraph<TreePerspectiveState> graph = new StateGraph<>(TreePerspectiveState::new);

        graph.addNode("debate", AsyncNodeAction.node_async(nodes.debateNode()));
        graph.addNode("shouldContinue", AsyncNodeAction.node_async(nodes.shouldContinue()));
        graph.addNode("summary", AsyncNodeAction.node_async(nodes.summaryNode()));

        graph.setEntryPoint("debate");
        graph.addEdge("debate", "shouldContinue");
        graph.addEdge("summary", StateGraph.END);

        // 条件路由: shouldContinue → "debate" 或 "summary"
        EdgeAction<TreePerspectiveState> condition = state ->
                state.value(TreePerspectiveState.NEXT).map(Object::toString).orElse("summary");
        graph.addConditionalEdges("shouldContinue", AsyncEdgeAction.edge_async(condition),
                Map.of("debate", "debate", "summary", "summary"));

        return graph;
    }
}
