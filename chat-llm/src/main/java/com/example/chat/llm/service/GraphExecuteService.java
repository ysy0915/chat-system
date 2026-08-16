package com.example.chat.llm.service;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.dto.LangGraphResponse;
import com.example.chat.dto.LangGraphResponse.StepTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>自研 LangGraph 风格图执行引擎</h2>
 *
 * <p>v3 能力：</p>
 * <ul>
 *   <li><b>逻辑节点</b> (nodeType=logic) — 不调 LLM，执行 {@code compare / increment} 表达式</li>
 *   <li><b>并行分支</b> (branches) — 一个节点内多个 LLM 并行调用，各自流式回调</li>
 *   <li><b>状态写入</b> (sink / sinkAppend) — 节点/分支输出可写入指定 state 键</li>
 *   <li><b>重试自愈</b> (retryCount / fallbackNodeId)</li>
 *   <li><b>流式事件</b> (GraphStreamEvent) — nodeId/branchId 标识</li>
 * </ul>
 */
@Service
@SuppressWarnings("PMD.CyclomaticComplexity") // 类级复杂度来自字段初始化器/流式匿名类，业务方法已分别豁免
public class GraphExecuteService {

    private static final Logger log = LoggerFactory.getLogger(GraphExecuteService.class);

    @Autowired
    private LLMInvokeService llmInvokeService;

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*state\\.([\\w.\\[\\]-]+)\\s*}}");
    private static final Pattern COMPARE_PATTERN = Pattern.compile("(<=|>=|==|!=|<|>)");
    private static final Pattern STATE_PATH_TOKEN = Pattern.compile("(\\w+)|\\[(-?\\d+)\\]");
    private static final int MAX_STEPS_DEFAULT = 20;

    private final ExecutorService branchExecutor = Executors.newFixedThreadPool(8,
            r -> { Thread t = new Thread(r, "graph-branch"); t.setDaemon(true); return t; });

    // ───────────────────────── 同步执行 ─────────────────────────

    /**
     * 同步执行图：从 {@code entryPoint} 开始按边遍历节点，直到终止节点或达到 maxSteps。
     * <p>逻辑节点（compare/increment）不调 LLM；普通节点调用 LLM 并应用 sink 写入状态。</p>
     *
     * @param request 图执行请求（节点/边/状态/入口点）
     * @return 图执行结果，含最终状态与逐步执行轨迹；参数校验失败时返回 fail 响应
     */
    @SuppressWarnings("PMD.NPathComplexity") // 图遍历主循环：条件跳转/逻辑节点分流/状态写回，拆分会破坏状态机
    public LangGraphResponse execute(LangGraphRequest request) {
        if (request.getMaxSteps() == null) request.setMaxSteps(MAX_STEPS_DEFAULT);
        try {
            validate(request);
        } catch (Exception e) {
            log.error("[Graph] 参数校验失败: {}", e.getMessage());
            return LangGraphResponse.fail(e.getMessage());
        }

        Map<String, Object> state = new HashMap<>(request.getState() != null ? request.getState() : new HashMap<>());
        List<StepTrace> traces = new ArrayList<>();
        Map<String, LangGraphRequest.GraphNode> nodeIndex = buildNodeIndex(request);
        java.util.Set<String> visited = new java.util.HashSet<>();

        String currentNodeId = request.getEntryPoint();

        for (int step = 0; step < request.getMaxSteps(); step++) {
            if (currentNodeId == null) break;

            // 环告警：重复访问同一节点提示图可能存在环（A→B→A）。
            // 注意：不直接终止——合法的「有限循环图」（配合 increment 计数 + 条件边退出）
            // 依赖重复访问，真正防死循环仍由 maxSteps 兜底。
            if (!visited.add(currentNodeId)) {
                log.warn("[Graph] 疑似环：节点 {} 在第 {} 步被重复访问（maxSteps={} 兜底终止）",
                        currentNodeId, step, request.getMaxSteps());
            }

            LangGraphRequest.GraphNode node = nodeIndex.get(currentNodeId);
            if (node == null) {
                log.warn("[Graph] 节点不存在: {}", currentNodeId);
                break;
            }

            long nodeStart = System.currentTimeMillis();
            String output;
            try {
                output = executeNode(node, request, state, traces, null);
            } catch (Exception e) {
                log.error("[Graph] 节点 {} 执行异常: {}", node.getId(), e.getMessage(), e);
                traces.add(buildTrace(node, "ERROR", e.getMessage(), System.currentTimeMillis() - nodeStart));
                break;
            }
            applySink(node, output, state);
            state.put("__lastOutput", output);

            if (traces != null && !nodeIsBranched(node)) {
                traces.add(buildTrace(node, "EXECUTED", output, System.currentTimeMillis() - nodeStart));
            }

            if (node.isTerminal()) {
                traces.add(buildTrace(node, "TERMINAL", output, System.currentTimeMillis() - nodeStart));
                currentNodeId = null;
                break;
            }

            String nextNodeId = resolveNext(node, output, request, state);
            if (nextNodeId == null) break;
            currentNodeId = nextNodeId;
        }

        if (currentNodeId != null) {
            log.warn("[Graph] 达到 maxSteps={} 仍未结束，强制终止", request.getMaxSteps());
        }

        LangGraphResponse response = new LangGraphResponse();
        response.setSuccess(true);
        response.setFinalState(state);
        response.setTrace(traces);
        response.setTotalSteps(traces.size());
        return response;
    }

    // ───────────────────────── 流式执行（v3：分支事件） ─────────────────────────

    /**
     * 流式执行图。事件回调带 nodeId/branchId。
     *
     * @param onEvent 流式事件回调 (GraphStreamEvent)
     * @param onDone  完成回调 (true=成功)
     */
    @SuppressWarnings("PMD.NPathComplexity") // 流式图遍历：异步事件转发/条件跳转/完成回调，拆分破坏回调时序
    public void executeStream(LangGraphRequest request,
                              Consumer<GraphStreamEvent> onEvent,
                              Consumer<Boolean> onDone) {
        if (request.getMaxSteps() == null) request.setMaxSteps(MAX_STEPS_DEFAULT);
        try {
            validate(request);
        } catch (Exception e) {
            log.error("[Graph] 参数校验失败: {}", e.getMessage());
            onDone.accept(false);
            return;
        }

        Map<String, Object> state = new HashMap<>(request.getState() != null ? request.getState() : new HashMap<>());
        Map<String, LangGraphRequest.GraphNode> nodeIndex = buildNodeIndex(request);
        java.util.Set<String> visited = new java.util.HashSet<>();

        String currentNodeId = request.getEntryPoint();

        try {
            for (int step = 0; step < request.getMaxSteps(); step++) {
                if (currentNodeId == null) break;

                if (!visited.add(currentNodeId)) {
                    log.warn("[Graph] 疑似环：节点 {} 在第 {} 步被重复访问（maxSteps={} 兜底终止）",
                            currentNodeId, step, request.getMaxSteps());
                }

                LangGraphRequest.GraphNode node = nodeIndex.get(currentNodeId);
                if (node == null) {
                    log.warn("[Graph] 节点不存在: {}", currentNodeId);
                    break;
                }

                onEvent.accept(GraphStreamEvent.nodeStart(node.getId()));

                String output = executeNode(node, request, state, null, onEvent);
                applySink(node, output, state);
                state.put("__lastOutput", output);

                onEvent.accept(GraphStreamEvent.nodeEnd(node.getId()));

                if (node.isTerminal()) {
                    currentNodeId = null;
                    break;
                }

                String nextNodeId = resolveNext(node, output, request, state);
                if (nextNodeId == null) break;
                currentNodeId = nextNodeId;
            }
            if (currentNodeId != null) {
                log.warn("[Graph] 达到 maxSteps={} 仍未结束，强制终止", request.getMaxSteps());
            }
            onDone.accept(true);
        } catch (Exception e) {
            log.error("[Graph] 流式执行异常: {}", e.getMessage(), e);
            onDone.accept(false);
        }
    }

    // ───────────────────────── 节点执行 ─────────────────────────

    private boolean nodeIsBranched(LangGraphRequest.GraphNode node) {
        return node.getBranches() != null && !node.getBranches().isEmpty();
    }

    private StepTrace buildTrace(LangGraphRequest.GraphNode node, String label, String output, long elapsed) {
        StepTrace trace = new StepTrace();
        trace.setNodeId(node.getId());
        trace.setLabel(label);
        trace.setOutput(output != null ? output : "");
        trace.setElapsedMs(elapsed);
        return trace;
    }

    /**
     * 执行单个节点，返回聚合输出。
     *
     * @param onEvent 流式事件回调（同步执行传 null）
     */
    private String executeNode(LangGraphRequest.GraphNode node,
                               LangGraphRequest request,
                               Map<String, Object> state,
                               List<StepTrace> traces,
                               Consumer<GraphStreamEvent> onEvent) {
        // 逻辑节点
        if ("logic".equals(node.getNodeType()) || node.getLogic() != null) {
            return executeLogicNode(node, state);
        }

        // 并行分支节点
        if (node.getBranches() != null && !node.getBranches().isEmpty()) {
            return executeBranches(node, request, state, traces, onEvent);
        }

        // 普通 LLM 节点（带重试）
        String model = node.getModel() != null ? node.getModel() : request.getModel();
        double temp = node.getTemperature() != null ? node.getTemperature()
                : (request.getTemperature() != null ? request.getTemperature() : 0.7);

        String provider = node.getProvider() != null ? node.getProvider() : request.getProvider();
        String system = node.getSystemPrompt();
        String user = renderTemplate(node.getUserPrompt(), state);
        LangChainRequest lc = buildLangChainRequest(request, provider, model, temp, system, user);
        return invokeWithRetry(node, request, lc, state, traces, onEvent, null);
    }

    // ── 并行分支执行 ──────────────────────────────────────

    @SuppressWarnings("PMD.NPathComplexity") // 分支并行汇聚：快照/合并/去重/流式广播，拆分破坏并发状态管理
    private String executeBranches(LangGraphRequest.GraphNode node,
                                   LangGraphRequest request,
                                   Map<String, Object> state,
                                   List<StepTrace> traces,
                                   Consumer<GraphStreamEvent> onEvent) {
        List<LangGraphRequest.GraphBranch> branches = node.getBranches();
        StringBuilder aggregate = new StringBuilder();

        // 分支并行执行：每个分支持独立 state 副本，彻底避免并发写共享 HashMap。
        // 分支内的逻辑节点（如 increment）写入只影响本分支副本，最终由主线程统一按 sink 合并写回。
        Map<String, Object> branchState = new HashMap<>(state);

        List<CompletableFuture<BranchResult>> futures = new ArrayList<>();
        for (LangGraphRequest.GraphBranch branch : branches) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    // 独立副本：隔离分支间状态，杜绝 HashMap 并发写导致的数据丢失/死循环
                    Map<String, Object> localState = new HashMap<>(branchState);
                    String output = executeBranch(branch, node, request, localState, onEvent);
                    return new BranchResult(branch.getId(), output, false);
                } catch (Exception e) {
                    log.error("[Graph] 分支 {} 执行失败: {}", branch.getId(), e.getMessage(), e);
                    return new BranchResult(branch.getId(), "", true);
                }
            }, branchExecutor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Graph] 分支并行等待异常: {}", e.getMessage());
        }

        List<BranchResult> results = new ArrayList<>();
        for (CompletableFuture<BranchResult> f : futures) {
            try {
                results.add(f.get(1, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(new BranchResult("?", "", true));
            }
        }

        // 主线程统一按 sink 写回 state
        Map<String, LangGraphRequest.GraphBranch> branchById = new HashMap<>();
        for (LangGraphRequest.GraphBranch b : branches) branchById.put(b.getId(), b);

        for (BranchResult r : results) {
            if (r.error()) continue;
            LangGraphRequest.GraphBranch branch = branchById.get(r.branchId());
            if (branch != null) {
                String sinkKey = branch.getSink() != null ? branch.getSink()
                        : node.getId() + "." + branch.getId();
                writeSink(state, sinkKey, r.output(), branch.isSinkAppend());
            }
            if (aggregate.length() > 0) aggregate.append('\n');
            aggregate.append('【').append(r.branchId()).append('】').append(r.output());
        }

        // 记录 trace
        if (traces != null) {
            for (BranchResult r : results) {
                StepTrace trace = new StepTrace();
                trace.setNodeId(node.getId());
                trace.setLabel("BRANCH_EXECUTED");
                trace.setOutput(r.output());
                trace.setElapsedMs(0);
                traces.add(trace);
            }
        }
        return aggregate.toString();
    }

    private record BranchResult(String branchId, String output, boolean error) {}

    /** 执行单个分支（流式时逐 token 回调）。返回完整输出。 */
    @SuppressWarnings("PMD.NPathComplexity") // 分支模型/温度/提示词三级覆盖解析，拆分为辅助方法收益有限
    private String executeBranch(LangGraphRequest.GraphBranch branch,
                                 LangGraphRequest.GraphNode node,
                                 LangGraphRequest request,
                                 Map<String, Object> state,
                                 Consumer<GraphStreamEvent> onEvent) {
        String model = branch.getModel() != null ? branch.getModel()
                : (node.getModel() != null ? node.getModel() : request.getModel());
        double temp = branch.getTemperature() != null ? branch.getTemperature()
                : (node.getTemperature() != null ? node.getTemperature()
                : (request.getTemperature() != null ? request.getTemperature() : 0.7));

        String provider = branch.getProvider() != null ? branch.getProvider()
                : (node.getProvider() != null ? node.getProvider() : request.getProvider());
        String system = branch.getSystemPrompt() != null ? branch.getSystemPrompt() : node.getSystemPrompt();
        String user = renderTemplate(branch.getUserPrompt() != null ? branch.getUserPrompt() : node.getUserPrompt(), state);

        LangChainRequest lc = buildLangChainRequest(request, provider, model, temp, system, user);

        if (onEvent != null) {
            onEvent.accept(GraphStreamEvent.branchStart(node.getId(), branch.getId()));
        }

        StringBuilder sb = new StringBuilder();
        String output = invokeWithRetry(node, request, lc, state, null, onEvent, branch);
        sb.append(output);

        if (onEvent != null) {
            onEvent.accept(GraphStreamEvent.branchEnd(node.getId(), branch.getId(), sb.toString()));
        }
        return sb.toString();
    }

    /** 构建 LangChainRequest：systemPrompt + messages 列表 */
    private LangChainRequest buildLangChainRequest(LangGraphRequest request,
                                                   String provider,
                                                   String model,
                                                   double temp,
                                                   String system,
                                                   String user) {
        LangChainRequest lc = new LangChainRequest();
        lc.setProvider(provider != null ? provider : request.getProvider());
        lc.setModel(model);
        lc.setTemperature(temp);
        lc.setMaxTokens(request.getMaxTokens());
        lc.setSystemPrompt(system);

        List<Map<String, Object>> messages = new ArrayList<>();
        if (user != null && !user.isBlank()) {
            Map<String, Object> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", user);
            messages.add(userMsg);
        }
        lc.setMessages(messages);
        return lc;
    }

    // ── 重试执行 LLM ─────────────────────────────────────

    private String invokeWithRetry(LangGraphRequest.GraphNode node,
                                   LangGraphRequest request,
                                   LangChainRequest lc,
                                   Map<String, Object> state,
                                   List<StepTrace> traces,
                                   Consumer<GraphStreamEvent> onEvent,
                                   LangGraphRequest.GraphBranch branch) {
        int maxRetry = node.getRetryCount();
        long backoff = node.getRetryBackoffMs();

        for (int attempt = 0; attempt <= maxRetry; attempt++) {
            try {
                if (onEvent != null) {
                    return invokeStreamAndJoin(lc, node.getId(), branch != null ? branch.getId() : null, onEvent);
                }
                String output = llmInvokeService.invoke(lc).getContent();
                if (output == null || output.isBlank()) {
                    throw new IllegalStateException("LLM 返回空内容");
                }
                return output;
            } catch (Exception e) {
                if (attempt < maxRetry) {
                    log.warn("[Graph] 节点 {} 第 {} 次调用失败: {}，{}ms 后重试",
                            node.getId(), attempt + 1, e.getMessage(), backoff);
                    sleepQuietly(backoff);
                } else {
                    // 重试耗尽：走降级节点或抛异常
                    if (node.getFallbackNodeId() != null) {
                        log.warn("[Graph] 节点 {} 重试耗尽，跳转降级节点 {}", node.getId(), node.getFallbackNodeId());
                        LangGraphRequest.GraphNode fallback = findNode(request, node.getFallbackNodeId());
                        if (fallback != null) {
                            return executeNode(fallback, request, state, traces, onEvent);
                        }
                    }
                    throw e;
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String invokeStreamAndJoin(LangChainRequest lc,
                                       String nodeId,
                                       String branchId,
                                       Consumer<GraphStreamEvent> onEvent) {
        // 线程安全：invokeStream 的回调可能在不同线程执行（底层 strategy 可能是异步 SSE），
        // 用 StringBuffer + CompletableFuture 显式等待完成，而非假设 invokeStream 同步返回即完成。
        StringBuffer sb = new StringBuffer();
        CompletableFuture<String> done = new CompletableFuture<>();
        try {
            llmInvokeService.invokeStream(lc,
                    chunk -> {
                        sb.append(chunk);
                        onEvent.accept(GraphStreamEvent.delta(nodeId, branchId, chunk));
                    },
                    () -> done.complete(sb.toString()),
                    done::completeExceptionally);
            // 阻塞等待流式完成（带超时兜底，避免底层 onComplete 永远不回调导致线程悬挂）
            return done.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Graph] 流式调用异常 node={} branch={}: {}", nodeId, branchId, e.getMessage(), e);
            throw new IllegalStateException("流式调用失败: " + e.getMessage(), e);
        }
    }

    // ── 逻辑节点 ─────────────────────────────────────────

    /**
     * 执行逻辑节点，不调 LLM。
     * 支持表达式：
     *   compare:{{state.a}} < {{state.b}}   → 返回 "true"/"false"
     *   increment:key:delta                 → 自增 state[key] 并返回新值
     */
    private String executeLogicNode(LangGraphRequest.GraphNode node, Map<String, Object> state) {
        String logic = node.getLogic();
        if (logic == null || logic.isBlank()) return "";

        if (logic.startsWith("compare:")) {
            String expr = logic.substring("compare:".length()).trim();
            String rendered = renderTemplate(expr, state);
            Matcher m = COMPARE_PATTERN.matcher(rendered);
            if (m.find()) {
                String op = m.group(1);
                String left = rendered.substring(0, m.start()).trim();
                String right = rendered.substring(m.end()).trim();
                boolean result = compareValues(left, right, op);
                return String.valueOf(result);
            }
            log.warn("[Graph] compare 表达式格式错误: {}", rendered);
            return "false";
        }

        if (logic.startsWith("increment:")) {
            String[] parts = logic.substring("increment:".length()).split(":");
            if (parts.length >= 1) {
                String key = parts[0].trim();
                int delta = parts.length >= 2 ? parseIntSafe(parts[1]) : 1;
                int cur = parseIntSafe(String.valueOf(state.getOrDefault(key, 0)));
                int next = cur + delta;
                state.put(key, next);
                return String.valueOf(next);
            }
            return "0";
        }

        // 其他逻辑：直接渲染模板输出
        return renderTemplate(logic, state);
    }

    private boolean compareValues(String left, String right, String op) {
        Double a = parseNum(left);
        Double b = parseNum(right);
        if (a != null && b != null) {
            return switch (op) {
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                case ">=" -> a >= b;
                case "==" -> a.equals(b);
                case "!=" -> !a.equals(b);
                default -> false;
            };
        }
        String la = left.trim();
        String lb = right.trim();
        return switch (op) {
            case "==" -> la.equals(lb);
            case "!=" -> !la.equals(lb);
            default -> false;
        };
    }

    // ── 状态写入 ─────────────────────────────────────────

    /** 按 sink 写 state：append 模式追加到 List，否则覆盖 */
    private void writeSink(Map<String, Object> state, String key, String output, boolean append) {
        if (append) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) state.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(output);
        } else {
            state.put(key, output);
        }
    }

    private void applySink(LangGraphRequest.GraphNode node, String output, Map<String, Object> state) {
        String key = node.getSink() != null ? node.getSink() : node.getId();
        writeSink(state, key, output, node.isSinkAppend());
    }

    // ── 路由 ─────────────────────────────────────────────

    @SuppressWarnings("PMD.NPathComplexity")
    // router/普通节点双分支×条件边/默认边多轮评估，拆方法反而割裂路由语义
    private String resolveNext(LangGraphRequest.GraphNode node,
                               String output,
                               LangGraphRequest request,
                               Map<String, Object> state) {
        List<LangGraphRequest.GraphEdge> outgoing = new ArrayList<>();
        for (LangGraphRequest.GraphEdge edge : request.getEdges()) {
            if (Objects.equals(edge.getFrom(), node.getId())) outgoing.add(edge);
        }
        if (outgoing.isEmpty()) return null;

        // 节点是 router 时，用 LLM 输出匹配条件边
        if (node.isRouter()) {
            String o = output != null ? output : String.valueOf(state.get("__lastOutput"));
            for (LangGraphRequest.GraphEdge edge : outgoing) {
                if (edge.getCondition() != null && evaluateCondition(edge.getCondition(), o, state)) {
                    return edge.getTo();
                }
            }
            // 默认边
            for (LangGraphRequest.GraphEdge edge : outgoing) {
                if (edge.isDefaultRoute() || edge.getCondition() == null) return edge.getTo();
            }
            return null;
        }

        // 普通节点：若有条件边则评估，否则第一条边
        for (LangGraphRequest.GraphEdge edge : outgoing) {
            if (edge.getCondition() == null) return edge.getTo();
            String o = output != null ? output : String.valueOf(state.get("__lastOutput"));
            if (evaluateCondition(edge.getCondition(), o, state)) return edge.getTo();
        }
        for (LangGraphRequest.GraphEdge edge : outgoing) {
            if (edge.isDefaultRoute()) return edge.getTo();
        }
        return null;
    }

    private boolean evaluateCondition(String condition, String output, Map<String, Object> state) {
        // 渲染上下文：state 快照 + __output（LLM 输出）。TEMPLATE_PATTERN 约定 {{state.xxx}} 形式，
        // 故把 output 注入 state 副本，条件边可写 {{state.__output}} 引用 LLM 输出。
        Map<String, Object> ctx = new HashMap<>(state);
        ctx.put("__output", output != null ? output : "");
        String cond = renderTemplate(condition, ctx).trim();
        String o = output != null ? output : "";

        // 字符串条件：contains / equals（左操作数默认取 LLM 输出，也可显式 {{...}}）
        if (cond.startsWith("contains(") && cond.endsWith(")")) {
            String inner = cond.substring("contains(".length(), cond.length() - 1);
            String[] parts = splitArg(inner);
            if (parts.length >= 2) {
                return o.contains(parts[1]);
            }
        }
        if (cond.startsWith("equals(") && cond.endsWith(")")) {
            String inner = cond.substring("equals(".length(), cond.length() - 1);
            String[] parts = splitArg(inner);
            if (parts.length >= 2) {
                return o.trim().equals(parts[1]);
            }
        }

        // 数值比较：gt/gte/lt/lte/ne/eq，两个操作数均取自条件内参数（可引用 {{output}} / {{state.xxx}}）
        for (String op : new String[]{"gt", "gte", "lt", "lte", "ne", "eq"}) {
            String prefix = op + "(";
            if (cond.startsWith(prefix) && cond.endsWith(")")) {
                String inner = cond.substring(prefix.length(), cond.length() - 1);
                String[] parts = splitArg(inner);
                if (parts.length >= 2) {
                    Double leftNum = parseNum(parts[0]);
                    Double rightNum = parseNum(parts[1]);
                    if (leftNum != null && rightNum != null) {
                        return switch (op) {
                            case "gt" -> leftNum > rightNum;
                            case "gte" -> leftNum >= rightNum;
                            case "lt" -> leftNum < rightNum;
                            case "lte" -> leftNum <= rightNum;
                            case "ne" -> !leftNum.equals(rightNum);
                            case "eq" -> leftNum.equals(rightNum);
                            default -> false;
                        };
                    }
                }
            }
        }
        return false;
    }

    private String[] splitArg(String inner) {
        int idx = inner.indexOf(',');
        if (idx < 0) return new String[]{inner.trim(), ""};
        String a = inner.substring(0, idx).trim();
        String b = inner.substring(idx + 1).trim();
        b = b.replaceAll("^['\"]|['\"]$", "");
        return new String[]{a, b};
    }

    // ── 工具方法 ─────────────────────────────────────────

    private String renderTemplate(String template, Map<String, Object> state) {
        if (template == null) return "";
        Matcher m = TEMPLATE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String replacement = resolveStateValue(state, key);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolveStateValue(Map<String, Object> state, String keyPath) {
        Object current = state;
        Matcher tm = STATE_PATH_TOKEN.matcher(keyPath);
        while (tm.find()) {
            String word = tm.group(1);
            String idxStr = tm.group(2);
            if (word != null) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(word);
                } else {
                    return "";
                }
            } else if (idxStr != null) {
                if (current instanceof List<?> list) {
                    try {
                        int idx = Integer.parseInt(idxStr);
                        if (idx < 0) idx = list.size() + idx;  // 负索引：-1 取最后一项
                        current = list.get(idx);
                    } catch (Exception e) {
                        return "";
                    }
                } else {
                    return "";
                }
            }
        }
        if (current == null) return "";
        if (current instanceof List<?> list) {
            return String.join("\n", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(current);
    }

    private LangGraphRequest.GraphNode findNode(LangGraphRequest request, String nodeId) {
        for (LangGraphRequest.GraphNode node : request.getNodes()) {
            if (Objects.equals(node.getId(), nodeId)) return node;
        }
        return null;
    }

    /**
     * 构建节点索引：nodeId → GraphNode，主循环 O(1) 查找，替代逐次线性扫描。
     */
    private Map<String, LangGraphRequest.GraphNode> buildNodeIndex(LangGraphRequest request) {
        Map<String, LangGraphRequest.GraphNode> index = new HashMap<>();
        for (LangGraphRequest.GraphNode node : request.getNodes()) {
            if (node.getId() != null) {
                index.put(node.getId(), node);
            }
        }
        return index;
    }

    private void validate(LangGraphRequest request) {
        if (request.getEntryPoint() == null || request.getEntryPoint().isBlank()) {
            throw new IllegalArgumentException("entryPoint 不能为空");
        }
        if (request.getNodes() == null || request.getNodes().isEmpty()) {
            throw new IllegalArgumentException("nodes 不能为空");
        }
    }

    private void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private Double parseNum(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return null; }
    }
}
