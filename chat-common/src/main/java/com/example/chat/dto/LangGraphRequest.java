package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>LangGraph 风格请求</h2>
 *
 * <p>描述一个有向图：节点 → 边 → 状态 → 入口。</p>
 *
 * <h3>GraphNode 增强字段 (v3)</h3>
 * <ul>
 *   <li>{@code retryCount} — 节点执行失败时自动重试次数 (默认 0)</li>
 *   <li>{@code retryBackoffMs} — 每次重试的退避时间 (毫秒)</li>
 *   <li>{@code fallbackNodeId} — 重试耗尽后跳转的降级节点 (null 则直接终止)</li>
 *   <li>{@code nodeType} — llm (默认) | logic（不调 LLM，执行逻辑表达式）</li>
 *   <li>{@code logic} — 逻辑表达式：compare:{{state.a}} &lt; {{state.b}} | increment:key:delta</li>
 *   <li>{@code branches} — 并行分支节点（一个节点内多个 LLM 并行调用）</li>
 *   <li>{@code sink} — 节点输出写入 state 的键（默认 node.id）</li>
 *   <li>{@code sinkAppend} — 输出追加到 state[sink] 列表（默认 false 覆盖）</li>
 * </ul>
 */
@Schema(description = "LangGraph 图执行请求")
public class LangGraphRequest {

    @Schema(description = "模型提供商", example = "deepseek")
    private String provider;

    @Schema(description = "全局模型名称", example = "deepseek-chat")
    private String model;

    @Schema(description = "全局温度")
    private Double temperature;

    @Schema(description = "全局最大 Token 数")
    private Integer maxTokens;

    @NotEmpty
    @Schema(description = "入口节点 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String entryPoint;

    @Schema(description = "最大执行步数 (防死循环)", example = "10")
    private Integer maxSteps;

    @Schema(description = "初始状态 (KV)", example = "{\"topic\": \"天气\"}")
    private Map<String, Object> state = new LinkedHashMap<>();

    @NotEmpty
    @Schema(description = "图节点列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<GraphNode> nodes = new ArrayList<>();

    @Schema(description = "图边列表")
    private List<GraphEdge> edges = new ArrayList<>();

    @Schema(description = "分布式追踪 ID (自动透传)")
    private String traceId;

    // ── 嵌套类型 ──────────────────────────────────────────

    @Schema(description = "图节点 — LLM 执行 + 路由 + 自愈 + 并行分支")
    public static class GraphNode {
        @NotEmpty
        @Schema(description = "节点 ID (唯一)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String id;

        @Schema(description = "节点标签 (用于日志和追踪)")
        private String label;

        @Schema(description = "系统提示 (可选)")
        private String systemPrompt;

        @Schema(description = "用户提示 (支持 {{state.xxx}} 模板)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String userPrompt;

        @Schema(description = "本节点覆盖的模型 (默认用全局)")
        private String model;

        @Schema(description = "本节点覆盖的提供商 (默认用全局)")
        private String provider;

        @Schema(description = "本节点覆盖的温度 (默认用全局)")
        private Double temperature;

        @Schema(description = "是否为路由节点 (LLM 输出用于匹配条件边)")
        private boolean router;

        @Schema(description = "是否为终结节点 (执行到此停止 Graph)")
        private boolean terminal;

        @Schema(description = "工具列表 (function calling)")
        private List<Map<String, Object>> tools;

        // ── 自愈字段 ──────────────────────────────────

        @Schema(description = "失败重试次数 (默认 0)", example = "2")
        private int retryCount;

        @Schema(description = "重试退避 (毫秒)", example = "500")
        private long retryBackoffMs = 500;

        @Schema(description = "降级节点 ID (重试耗尽后跳转，null 则终止 Graph)", example = "fallback_summary")
        private String fallbackNodeId;

        // ── v3 并行/逻辑字段 ─────────────────────────

        @Schema(description = "节点类型: llm (默认) | logic（不调 LLM）", example = "llm")
        private String nodeType = "llm";

        @Schema(description = "逻辑表达式 (nodeType=logic): compare:{{state.a}} < {{state.b}} | increment:key:delta",
                example = "compare:{{state.round}} < {{state.maxRounds}}")
        private String logic;

        @Schema(description = "并行分支节点 (一个节点内多个 LLM 并行调用)")
        private List<GraphBranch> branches;

        @Schema(description = "节点输出写入 state 的键 (默认 node.id)")
        private String sink;

        @Schema(description = "输出追加到 state[sink] 列表 (默认 false 覆盖)")
        private boolean sinkAppend;

        // getters & setters

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

        public String getUserPrompt() { return userPrompt; }
        public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }

        public boolean isRouter() { return router; }
        public void setRouter(boolean router) { this.router = router; }

        public boolean isTerminal() { return terminal; }
        public void setTerminal(boolean terminal) { this.terminal = terminal; }

        public List<Map<String, Object>> getTools() { return tools; }
        public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }

        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

        public long getRetryBackoffMs() { return retryBackoffMs; }
        public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

        public String getFallbackNodeId() { return fallbackNodeId; }
        public void setFallbackNodeId(String fallbackNodeId) { this.fallbackNodeId = fallbackNodeId; }

        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }

        public String getLogic() { return logic; }
        public void setLogic(String logic) { this.logic = logic; }

        public List<GraphBranch> getBranches() { return branches; }
        public void setBranches(List<GraphBranch> branches) { this.branches = branches; }

        public String getSink() { return sink; }
        public void setSink(String sink) { this.sink = sink; }

        public boolean isSinkAppend() { return sinkAppend; }
        public void setSinkAppend(boolean sinkAppend) { this.sinkAppend = sinkAppend; }
    }

    @Schema(description = "并行分支 — 节点内一个独立的 LLM 调用")
    public static class GraphBranch {
        @NotEmpty
        @Schema(description = "分支 ID (节点内唯一)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String id;

        @Schema(description = "分支标签 (用于日志和追踪)")
        private String label;

        @Schema(description = "分支系统提示 (可选)")
        private String systemPrompt;

        @Schema(description = "分支用户提示 (支持 {{state.xxx}} 模板)", requiredMode = Schema.RequiredMode.REQUIRED)
        private String userPrompt;

        @Schema(description = "分支覆盖的模型 (默认用全局/节点)")
        private String model;

        @Schema(description = "分支覆盖的提供商 (默认用全局，多提供商场景必填)")
        private String provider;

        @Schema(description = "分支覆盖的温度")
        private Double temperature;

        @Schema(description = "分支输出写入 state 的键 (默认 node.id.branch.id)")
        private String sink;

        @Schema(description = "分支输出追加到 state[sink] 列表 (默认 false 覆盖)")
        private boolean sinkAppend;

        // getters & setters

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getSystemPrompt() { return systemPrompt; }
        public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

        public String getUserPrompt() { return userPrompt; }
        public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }

        public Double getTemperature() { return temperature; }
        public void setTemperature(Double temperature) { this.temperature = temperature; }

        public String getSink() { return sink; }
        public void setSink(String sink) { this.sink = sink; }

        public boolean isSinkAppend() { return sinkAppend; }
        public void setSinkAppend(boolean sinkAppend) { this.sinkAppend = sinkAppend; }
    }

    @Schema(description = "图边 — 有条件时是路由边")
    public static class GraphEdge {
        @Schema(description = "源节点 ID")
        private String from;

        @Schema(description = "目标节点 ID")
        private String to;

        @Schema(description = "边标签 (可选)")
        private String label;

        @Schema(description = "条件表达式: contains({{output}}, 'xx') | equals({{output}}, 'xx')")
        private String condition;

        @Schema(description = "是否为默认路由 (条件都不匹配时使用)", example = "true")
        private boolean defaultRoute;

        // getters & setters

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }

        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }

        public boolean isDefaultRoute() { return defaultRoute; }
        public void setDefaultRoute(boolean defaultRoute) { this.defaultRoute = defaultRoute; }
    }

    // ── 顶层 getters / setters ────────────────────────────

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public String getEntryPoint() { return entryPoint; }
    public void setEntryPoint(String entryPoint) { this.entryPoint = entryPoint; }

    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }

    public Map<String, Object> getState() { return state; }
    public void setState(Map<String, Object> state) { this.state = state != null ? state : new LinkedHashMap<>(); }

    public List<GraphNode> getNodes() { return nodes; }
    public void setNodes(List<GraphNode> nodes) { this.nodes = nodes != null ? nodes : new ArrayList<>(); }

    public List<GraphEdge> getEdges() { return edges; }
    public void setEdges(List<GraphEdge> edges) { this.edges = edges != null ? edges : new ArrayList<>(); }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}
