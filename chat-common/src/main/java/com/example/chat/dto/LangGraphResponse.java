package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LangGraph 图执行响应。
 */
@Schema(description = "LangGraph 图执行响应")
public class LangGraphResponse {

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "最终 AI 回复 (终端节点输出)")
    private String output;

    @Schema(description = "执行过程追踪")
    private List<StepTrace> trace = new ArrayList<>();

    @Schema(description = "总共执行的步骤数")
    private int totalSteps;

    @Schema(description = "总耗时 (毫秒)")
    private long elapsedMs;

    @Schema(description = "最终状态 (所有节点输出)")
    private Map<String, Object> finalState;

    @Schema(description = "错误信息 (失败时)")
    private String error;

    @Schema(description = "分布式追踪 ID")
    private String traceId;

    @Schema(description = "降级节点是否被触发")
    private boolean fallbackUsed;

    // ── 嵌套类型 ──────────────────────────────────────────

    @Schema(description = "单步追踪信息")
    public static class StepTrace {
        @Schema(description = "节点 ID")
        private String nodeId;

        @Schema(description = "节点标签")
        private String label;

        @Schema(description = "节点输入 (渲染后)")
        private Object input;

        @Schema(description = "节点输出")
        private Object output;

        @Schema(description = "原始返回内容")
        private String rawContent;

        @Schema(description = "本步耗时 (毫秒)")
        private long elapsedMs;

        @Schema(description = "重试次数 (本步)")
        private int retries;

        @Schema(description = "是否触发了降级")
        private boolean fallback;

        // getters & setters

        public String getNodeId() { return nodeId; }
        public void setNodeId(String nodeId) { this.nodeId = nodeId; }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public Object getInput() { return input; }
        public void setInput(Object input) { this.input = input; }

        public Object getOutput() { return output; }
        public void setOutput(Object output) { this.output = output; }

        public String getRawContent() { return rawContent; }
        public void setRawContent(String rawContent) { this.rawContent = rawContent; }

        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }

        public boolean isFallback() { return fallback; }
        public void setFallback(boolean fallback) { this.fallback = fallback; }
    }

    // ── 工厂方法 ──────────────────────────────────────────

    public static LangGraphResponse success() {
        LangGraphResponse r = new LangGraphResponse();
        r.success = true;
        return r;
    }

    public static LangGraphResponse fail(String error) {
        LangGraphResponse r = new LangGraphResponse();
        r.success = false;
        r.error = error;
        return r;
    }

    // ── getters / setters ─────────────────────────────────

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public List<StepTrace> getTrace() { return trace; }
    public void setTrace(List<StepTrace> trace) { this.trace = trace; }

    public int getTotalSteps() { return totalSteps; }
    public void setTotalSteps(int totalSteps) { this.totalSteps = totalSteps; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public Map<String, Object> getFinalState() { return finalState; }
    public void setFinalState(Map<String, Object> finalState) { this.finalState = finalState; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public boolean isFallbackUsed() { return fallbackUsed; }
    public void setFallbackUsed(boolean fallbackUsed) { this.fallbackUsed = fallbackUsed; }
}
