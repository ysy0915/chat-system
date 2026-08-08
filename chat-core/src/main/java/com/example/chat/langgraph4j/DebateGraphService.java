package com.example.chat.langgraph4j;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.LLMInvoker;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.EdgeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LangGraph4j 辩论图服务
 *
 * 图式工作流：
 *   START → debate（三方并行辩论）→ shouldContinue
 *                                    ├─ "debate" → debate（循环）
 *                                    └─ "summary" → summary → END
 */
@Service
@ConditionalOnProperty(name = "app.langgraph4j.enabled", havingValue = "true")
public class DebateGraphService {

    private static final Logger log = LoggerFactory.getLogger(DebateGraphService.class);

    @Autowired
    private LLMInvoker llmInvoker;
    @Autowired
    private BroadcastService broadcastService;
    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @Value("${app.langgraph4j.debate.rounds:3}")
    private int maxRounds;

    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(3);

    public DebateState execute(String reqId, Long userId, String topic) throws Exception {
        log.info("[DebateGraph] 开始辩论 reqId={} userId={} topic={}", reqId, userId, topic);

        List<ModelConfig> chatModels = modelConfigRepository.findAllEnabledByType("chat");
        ModelConfig proModel = chatModels.stream()
                .filter(m -> "doubao".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要豆包模型"));
        ModelConfig conModel = chatModels.stream()
                .filter(m -> "deepseek".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要 DeepSeek 模型"));
        ModelConfig summaryModel = chatModels.stream()
                .filter(m -> "qwen".equalsIgnoreCase(m.provider)).findFirst()
                .orElseThrow(() -> new RuntimeException("需要千问模型"));

        DebateNodes nodes = new DebateNodes(llmInvoker, broadcastService,
                proModel, conModel, summaryModel, defaultApiKey, parallelExecutor);

        StateGraph<DebateState> graph = buildGraph(nodes);
        CompiledGraph<DebateState> compiled = graph.compile();

        Map<String, Object> initData = new HashMap<>();
        initData.put(DebateState.TOPIC, topic);
        initData.put(DebateState.USER_ID, userId != null ? userId : 0L);
        initData.put(DebateState.REQ_ID, reqId != null ? reqId : "");
        initData.put(DebateState.CURRENT_ROUND, 0);
        initData.put(DebateState.MAX_ROUNDS, maxRounds);
        initData.put(DebateState.PRO_ARGUMENTS, new ArrayList<String>());
        initData.put(DebateState.CON_ARGUMENTS, new ArrayList<String>());
        initData.put(DebateState.NEUTRAL_ARGUMENTS, new ArrayList<String>());

        var result = compiled.invoke(initData);
        DebateState finalState = result.orElseGet(() -> new DebateState(initData));

        log.info("[DebateGraph] 辩论完成 proRounds={} conRounds={} neutralRounds={} summaryLen={}",
                finalState.getProArguments().size(),
                finalState.getConArguments().size(),
                finalState.getNeutralArguments().size(),
                finalState.getSummary() != null ? finalState.getSummary().length() : 0);

        return finalState;
    }

    private StateGraph<DebateState> buildGraph(DebateNodes nodes) throws Exception {
        StateGraph<DebateState> graph = new StateGraph<>(DebateState::new);

        graph.addNode("debate", AsyncNodeAction.node_async(nodes.debateNode()));
        graph.addNode("shouldContinue", AsyncNodeAction.node_async(nodes.shouldContinue()));
        graph.addNode("summary", AsyncNodeAction.node_async(nodes.summaryNode()));

        graph.setEntryPoint("debate");

        graph.addEdge("debate", "shouldContinue");
        graph.addEdge("summary", StateGraph.END);

        // 条件边：shouldContinue 根据返回值决定下一步
        EdgeAction<DebateState> condition = state -> {
            return state.value(DebateState.NEXT).map(v -> v.toString()).orElse("summary");
        };
        graph.addConditionalEdges("shouldContinue", AsyncEdgeAction.edge_async(condition),
                Map.of("debate", "debate", "summary", "summary"));

        return graph;
    }
}
