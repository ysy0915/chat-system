package com.example.chat.observability;

/**
 * 调用链路记录实体
 * 字段序列化为 JSON 存 Redis
 */
public class CallTrace {

    public String traceId;
    public String scene;
    public String provider;
    public String model;
    public long startTime;
    public long endTime;
    public long latency;
    /** SUCCESS / FAIL */
    public String status;
    public String errorMessage;
    /** 工具调用记录（逗号分隔的工具名，可空） */
    public String toolCalls;

    public CallTrace() {
    }

    public CallTrace(String traceId, String scene, String provider, String model,
                     long startTime, long endTime, long latency,
                     String status, String errorMessage, String toolCalls) {
        this.traceId = traceId;
        this.scene = scene;
        this.provider = provider;
        this.model = model;
        this.startTime = startTime;
        this.endTime = endTime;
        this.latency = latency;
        this.status = status;
        this.errorMessage = errorMessage;
        this.toolCalls = toolCalls;
    }

    /**
     * 序列化为简单 JSON（避免引入 Jackson 依赖）
     */
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getEndTime() { return endTime; }
    public void setEndTime(long endTime) { this.endTime = endTime; }

    public long getLatency() { return latency; }
    public void setLatency(long latency) { this.latency = latency; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getToolCalls() { return toolCalls; }
    public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"traceId\":\"").append(escape(traceId)).append("\",");
        sb.append("\"scene\":\"").append(escape(scene)).append("\",");
        sb.append("\"provider\":\"").append(escape(provider)).append("\",");
        sb.append("\"model\":\"").append(escape(model)).append("\",");
        sb.append("\"startTime\":").append(startTime).append(',');
        sb.append("\"endTime\":").append(endTime).append(',');
        sb.append("\"latency\":").append(latency).append(',');
        sb.append("\"status\":\"").append(escape(status)).append("\",");
        sb.append("\"errorMessage\":\"").append(escape(errorMessage)).append("\",");
        sb.append("\"toolCalls\":\"").append(escape(toolCalls)).append('"');
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
