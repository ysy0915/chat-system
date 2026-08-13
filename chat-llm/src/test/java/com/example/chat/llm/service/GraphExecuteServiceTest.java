package com.example.chat.llm.service;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.LangGraphResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自研图执行引擎测试 — 逻辑节点 / 模板渲染 / 条件路由 / 重试自愈 / 并行分支 / 流式事件。
 */
@ExtendWith(MockitoExtension.class)
class GraphExecuteServiceTest {

    @Mock
    private LLMInvokeService llmInvokeService;

    @InjectMocks
    private GraphExecuteService service;

    // ============ 逻辑节点 ============

    @Test
    void shouldExecuteLogicCompareNode() {
        LangGraphRequest req = request("judge",
                List.of(logicNode("judge", "compare:{{state.a}} < {{state.b}}")),
                List.of(), Map.of("a", 1, "b", 2));

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals("true", resp.getFinalState().get("judge"));
        assertEquals("true", resp.getFinalState().get("__lastOutput"));
    }

    @Test
    void shouldExecuteLogicIncrementNode() {
        LangGraphRequest req = request("counter",
                List.of(logicNode("counter", "increment:round:1")),
                List.of(), Map.of("round", 0));

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals(1, resp.getFinalState().get("round"));
    }

    @Test
    void shouldCompareStringEquality() {
        LangGraphRequest req = request("judge",
                List.of(logicNode("judge", "compare:{{state.word}} == {{state.target}}")),
                List.of(), Map.of("word", "AI", "target", "AI"));

        LangGraphResponse resp = service.execute(req);

        assertEquals("true", resp.getFinalState().get("judge"));
    }

    // ============ 模板渲染 ============

    @Test
    void shouldRenderTemplateFromStateIntoMessages() {
        LangGraphRequest.GraphNode n = llmNode("n1", "分析 {{state.topic}}，轮次 {{state.round}}");
        LangGraphRequest req = request("n1", List.of(n), List.of(),
                Map.of("topic", "AI 治理", "round", 3));
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("结论", "deepseek", "deepseek-chat"));

        service.execute(req);

        ArgumentCaptor<LangChainRequest> captor = ArgumentCaptor.forClass(LangChainRequest.class);
        verify(llmInvokeService).invoke(captor.capture());
        LangChainRequest lc = captor.getValue();
        assertEquals("分析 AI 治理，轮次 3", lc.getMessages().get(0).get("content"));
        assertEquals(0.7, lc.getTemperature());
    }

    @Test
    void shouldRenderListStateJoinedByNewline() {
        LangGraphRequest.GraphNode n = llmNode("n1", "内容：{{state.items}}");
        LangGraphRequest req = request("n1", List.of(n), List.of(),
                Map.of("items", List.of("甲", "乙")));
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("ok", "deepseek", "deepseek-chat"));

        service.execute(req);

        ArgumentCaptor<LangChainRequest> captor = ArgumentCaptor.forClass(LangChainRequest.class);
        verify(llmInvokeService).invoke(captor.capture());
        assertEquals("内容：甲\n乙", captor.getValue().getMessages().get(0).get("content"));
    }

    // ============ sink 状态写入 ============

    @Test
    void shouldWriteNodeOutputToSink() {
        LangGraphRequest.GraphNode n = llmNode("n1", "生成");
        n.setSink("topic");
        LangGraphRequest req = request("n1", List.of(n), List.of(), Map.of());
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("AI 输出", "deepseek", "deepseek-chat"));

        LangGraphResponse resp = service.execute(req);

        assertEquals("AI 输出", resp.getFinalState().get("topic"));
    }

    @Test
    void shouldAppendToSinkListWhenSinkAppend() {
        LangGraphRequest.GraphNode n = llmNode("n1", "追加");
        n.setSink("notes");
        n.setSinkAppend(true);
        LangGraphRequest req = request("n1", List.of(n), List.of(), Map.of());
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("笔记1", "deepseek", "deepseek-chat"));

        LangGraphResponse resp = service.execute(req);

        assertEquals(List.of("笔记1"), resp.getFinalState().get("notes"));
    }

    // ============ 条件路由 ============

    @Test
    void shouldRouteByConditionEdge() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "分析");
        LangGraphRequest.GraphNode n2 = terminalNode("n2", "收尾");
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("yes 完全同意", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("n1", List.of(n1, n2),
                List.of(edge("n1", "n2", "contains({{output}}, 'yes')", false)), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        // n1 EXECUTED + n2 EXECUTED + n2 TERMINAL
        assertEquals(3, resp.getTrace().size());
        assertEquals("TERMINAL", resp.getTrace().get(2).getLabel());
    }

    @Test
    void shouldStopWhenNoConditionMatchesAndNoDefaultEdge() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "分析");
        LangGraphRequest.GraphNode n2 = terminalNode("n2", "收尾");
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("no 不同意", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("n1", List.of(n1, n2),
                List.of(edge("n1", "n2", "contains({{output}}, 'yes')", false)), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals(1, resp.getTrace().size());
    }

    @Test
    void shouldRouteByRouterNodeWithDefaultEdge() {
        LangGraphRequest.GraphNode router = llmNode("router", "判断");
        router.setRouter(true);
        LangGraphRequest.GraphNode ok = terminalNode("ok", "通过");
        LangGraphRequest.GraphNode fallback = terminalNode("fallback", "拒绝");
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("允许执行", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("router", List.of(router, ok, fallback), List.of(
                edge("router", "ok", "contains({{output}}, '允许')", false),
                edge("router", "fallback", null, true)), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertTrue(resp.getTrace().stream().anyMatch(t -> "ok".equals(t.getNodeId())));
    }

    // ============ 终止 / 步数控制 ============

    @Test
    void shouldStopAtTerminalNode() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "第一步");
        LangGraphRequest.GraphNode end = terminalNode("end", "结束");
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("输出1", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("n1", List.of(n1, end),
                List.of(edge("n1", "end", null, false)), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals(3, resp.getTrace().size());
        assertEquals("TERMINAL", resp.getTrace().get(2).getLabel());
    }

    @Test
    void shouldForceStopAtMaxSteps() {
        LangGraphRequest.GraphNode n1 = llmNode("loop", "循环");
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("x", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("loop", List.of(n1),
                List.of(edge("loop", "loop", null, false)), Map.of(), 3);

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals(3, resp.getTrace().size());
    }

    @Test
    void shouldHandleUnknownEntryNode() {
        LangGraphRequest req = request("unknown",
                List.of(logicNode("a", "increment:x")), List.of(), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals(0, resp.getTotalSteps());
    }

    // ============ 参数校验 ============

    @Test
    void shouldFailOnMissingEntryPoint() {
        LangGraphRequest req = new LangGraphRequest();
        req.setNodes(List.of(logicNode("a", "increment:x")));

        LangGraphResponse resp = service.execute(req);

        assertFalse(resp.isSuccess());
        assertNotNull(resp.getError());
    }

    @Test
    void shouldFailStreamingOnMissingEntryPoint() {
        LangGraphRequest req = new LangGraphRequest();
        req.setNodes(List.of(logicNode("a", "increment:x")));
        AtomicBoolean done = new AtomicBoolean(true);

        service.executeStream(req, e -> { }, done::set);

        assertFalse(done.get());
    }

    // ============ 重试自愈 ============

    @Test
    void shouldRetryUntilSuccess() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "重试");
        n1.setRetryCount(2);
        n1.setRetryBackoffMs(0);
        when(llmInvokeService.invoke(any()))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(LangChainResponse.ok("第二次成功", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("n1", List.of(n1), List.of(), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals("第二次成功", resp.getFinalState().get("n1"));
        verify(llmInvokeService, times(2)).invoke(any());
    }

    @Test
    void shouldJumpToFallbackNodeAfterRetriesExhausted() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "主节点");
        n1.setFallbackNodeId("fallback");
        LangGraphRequest.GraphNode fallback = logicNode("fallback", "compare:{{state.a}} == {{state.b}}");
        when(llmInvokeService.invoke(any())).thenThrow(new RuntimeException("boom"));
        LangGraphRequest req = request("n1", List.of(n1, fallback), List.of(),
                Map.of("a", 1, "b", 1));

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals("true", resp.getFinalState().get("n1"));
    }

    @Test
    void shouldTreatBlankLlmOutputAsFailure() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "空输出");
        // maxRetry=2 表示共 3 次尝试：空串 → 空白 → 有效内容
        n1.setRetryCount(2);
        n1.setRetryBackoffMs(0);
        when(llmInvokeService.invoke(any()))
                .thenReturn(LangChainResponse.ok("", "deepseek", "deepseek-chat"))
                .thenReturn(LangChainResponse.ok("   ", "deepseek", "deepseek-chat"))
                .thenReturn(LangChainResponse.ok("有效内容", "deepseek", "deepseek-chat"));
        LangGraphRequest req = request("n1", List.of(n1), List.of(), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        assertEquals("有效内容", resp.getFinalState().get("n1"));
        verify(llmInvokeService, times(3)).invoke(any());
    }

    // ============ 并行分支 ============

    @Test
    void shouldExecuteBranchesAndWriteSinks() {
        LangGraphRequest.GraphNode n1 = new LangGraphRequest.GraphNode();
        n1.setId("n1");
        n1.setUserPrompt("主");
        LangGraphRequest.GraphBranch b1 = branch("b1", "视角A");
        LangGraphRequest.GraphBranch b2 = branch("b2", "视角B");
        n1.setBranches(List.of(b1, b2));
        n1.setSink("summary");
        when(llmInvokeService.invoke(any())).thenAnswer(inv -> {
            LangChainRequest lc = inv.getArgument(0);
            String user = (String) lc.getMessages().get(0).get("content");
            return LangChainResponse.ok("结果[" + user + "]", "deepseek", "deepseek-chat");
        });
        LangGraphRequest req = request("n1", List.of(n1), List.of(), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        Map<String, Object> state = resp.getFinalState();
        assertEquals("结果[视角A]", state.get("n1.b1"));
        assertEquals("结果[视角B]", state.get("n1.b2"));
        String summary = (String) state.get("summary");
        assertTrue(summary.contains("结果[视角A]"));
        assertTrue(summary.contains("结果[视角B]"));
    }

    @Test
    void shouldIgnoreFailedBranchAndKeepOthers() {
        LangGraphRequest.GraphNode n1 = new LangGraphRequest.GraphNode();
        n1.setId("n1");
        n1.setUserPrompt("主");
        n1.setBranches(List.of(branch("b1", "失败分支"), branch("b2", "成功分支")));
        when(llmInvokeService.invoke(any())).thenAnswer(inv -> {
            LangChainRequest lc = inv.getArgument(0);
            String user = (String) lc.getMessages().get(0).get("content");
            if ("失败分支".equals(user)) {
                throw new RuntimeException("branch boom");
            }
            return LangChainResponse.ok("成功输出", "deepseek", "deepseek-chat");
        });
        LangGraphRequest req = request("n1", List.of(n1), List.of(), Map.of());

        LangGraphResponse resp = service.execute(req);

        assertTrue(resp.isSuccess());
        // 失败分支不写入 sink，成功分支正常写入
        assertNull(resp.getFinalState().get("n1.b1"));
        assertEquals("成功输出", resp.getFinalState().get("n1.b2"));
    }

    // ============ 流式执行 ============

    @Test
    void shouldStreamEventsInOrder() {
        LangGraphRequest.GraphNode n1 = llmNode("n1", "流式输出");
        doAnswer(inv -> {
            java.util.function.Consumer<String> chunk = inv.getArgument(1);
            Runnable done = inv.getArgument(2);
            chunk.accept("你");
            chunk.accept("好");
            done.run();
            return null;
        }).when(llmInvokeService).invokeStream(any(), any(), any(), any());
        List<GraphStreamEvent> events = new ArrayList<>();
        AtomicBoolean doneFlag = new AtomicBoolean(false);

        service.executeStream(request("n1", List.of(n1), List.of(), Map.of()),
                events::add, doneFlag::set);

        List<String> types = events.stream().map(GraphStreamEvent::getType).toList();
        assertEquals(List.of(GraphStreamEvent.TYPE_NODE_START,
                GraphStreamEvent.TYPE_DELTA, GraphStreamEvent.TYPE_DELTA,
                GraphStreamEvent.TYPE_NODE_END), types);
        assertEquals("你", events.get(1).getData());
        assertEquals("n1", events.get(1).getNodeId());
        assertTrue(doneFlag.get());
    }

    @Test
    void shouldStreamBranchEventsWithBranchId() {
        LangGraphRequest.GraphNode n1 = new LangGraphRequest.GraphNode();
        n1.setId("n1");
        n1.setUserPrompt("主");
        n1.setBranches(List.of(branch("b1", "视角A")));
        doAnswer(inv -> {
            java.util.function.Consumer<String> chunk = inv.getArgument(1);
            Runnable done = inv.getArgument(2);
            chunk.accept("tok");
            done.run();
            return null;
        }).when(llmInvokeService).invokeStream(any(), any(), any(), any());
        List<GraphStreamEvent> events = new ArrayList<>();

        service.executeStream(request("n1", List.of(n1), List.of(), Map.of()),
                events::add, ok -> { });

        assertTrue(events.stream().anyMatch(e ->
                GraphStreamEvent.TYPE_BRANCH_START.equals(e.getType())
                        && "b1".equals(e.getBranchId())));
        assertTrue(events.stream().anyMatch(e ->
                GraphStreamEvent.TYPE_DELTA.equals(e.getType()) && "b1".equals(e.getBranchId())));
    }

    // ============ helpers ============

    private LangGraphRequest request(String entry, List<LangGraphRequest.GraphNode> nodes,
                                     List<LangGraphRequest.GraphEdge> edges, Map<String, Object> state) {
        return request(entry, nodes, edges, state, 20);
    }

    private LangGraphRequest request(String entry, List<LangGraphRequest.GraphNode> nodes,
                                     List<LangGraphRequest.GraphEdge> edges,
                                     Map<String, Object> state, int maxSteps) {
        LangGraphRequest req = new LangGraphRequest();
        req.setEntryPoint(entry);
        req.setNodes(nodes);
        req.setEdges(edges);
        req.setState(state);
        req.setMaxSteps(maxSteps);
        req.setProvider("deepseek");
        req.setModel("deepseek-chat");
        return req;
    }

    private LangGraphRequest.GraphNode logicNode(String id, String logic) {
        LangGraphRequest.GraphNode n = new LangGraphRequest.GraphNode();
        n.setId(id);
        n.setNodeType("logic");
        n.setLogic(logic);
        return n;
    }

    private LangGraphRequest.GraphNode llmNode(String id, String userPrompt) {
        LangGraphRequest.GraphNode n = new LangGraphRequest.GraphNode();
        n.setId(id);
        n.setUserPrompt(userPrompt);
        return n;
    }

    private LangGraphRequest.GraphNode terminalNode(String id, String userPrompt) {
        LangGraphRequest.GraphNode n = llmNode(id, userPrompt);
        n.setTerminal(true);
        return n;
    }

    private LangGraphRequest.GraphEdge edge(String from, String to, String condition, boolean defaultRoute) {
        LangGraphRequest.GraphEdge e = new LangGraphRequest.GraphEdge();
        e.setFrom(from);
        e.setTo(to);
        e.setCondition(condition);
        e.setDefaultRoute(defaultRoute);
        return e;
    }

    private LangGraphRequest.GraphBranch branch(String id, String userPrompt) {
        LangGraphRequest.GraphBranch b = new LangGraphRequest.GraphBranch();
        b.setId(id);
        b.setUserPrompt(userPrompt);
        return b;
    }
}
