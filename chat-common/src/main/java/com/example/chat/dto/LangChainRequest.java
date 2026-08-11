package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;

/**
 * LangChain 风格请求 — 兼容 LangChain invoke() 的入参结构。
 */
@Schema(description = "LangChain 风格 LLM 调用请求")
public class LangChainRequest {

    @Schema(description = "模型提供商", example = "deepseek", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private String provider;

    @Schema(description = "模型名称", example = "deepseek-chat")
    private String model;

    @Schema(description = "消息列表", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    private List<Map<String, Object>> messages;

    @Schema(description = "温度 (0~2)", example = "0.7")
    private Double temperature;

    @Schema(description = "最大 Token 数", example = "4096")
    private Integer maxTokens;

    @Schema(description = "顶层 p 采样", example = "0.9")
    private Double topP;

    @Schema(description = "停止词列表")
    private List<String> stop;

    @Schema(description = "是否流式输出", example = "false")
    private Boolean stream = false;

    @Schema(description = "system prompt (可选，会自动前置插入 messages)")
    private String systemPrompt;

    @Schema(description = "扩展参数 (透传)")
    private Map<String, Object> extra;

    @Schema(description = "分布式追踪 ID (自动透传)")
    private String traceId;

    @Schema(description = "业务域 (缺省 CHAT) — 用于网关路由 & 流控/计费", example = "CHAT")
    private String bizType;

    // ---- getters / setters ----

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public List<Map<String, Object>> getMessages() { return messages; }
    public void setMessages(List<Map<String, Object>> messages) { this.messages = messages; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }

    public List<String> getStop() { return stop; }
    public void setStop(List<String> stop) { this.stop = stop; }

    public Boolean getStream() { return stream; }
    public void setStream(Boolean stream) { this.stream = stream; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public Map<String, Object> getExtra() { return extra; }
    public void setExtra(Map<String, Object> extra) { this.extra = extra; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
}
