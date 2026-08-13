package com.example.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * LangChain 风格 LLM 调用响应。
 */
@Schema(description = "LLM 调用响应")
public class LangChainResponse {

    @Schema(description = "是否成功")
    private boolean success;

    @Schema(description = "AI 回复文本")
    private String content;

    @Schema(description = "提供商", example = "deepseek")
    private String provider;

    @Schema(description = "模型名称", example = "deepseek-chat")
    private String model;

    @Schema(description = "总 Token 数")
    private Integer totalTokens;

    @Schema(description = "提示 Token 数")
    private Integer promptTokens;

    @Schema(description = "完成 Token 数")
    private Integer completionTokens;

    @Schema(description = "耗时 (毫秒)")
    private long elapsedMs;

    @Schema(description = "错误信息 (失败时)")
    private String error;

    @Schema(description = "工具调用列表 (function calling)")
    private List<Map<String, Object>> toolCalls;

    @Schema(description = "分布式追踪 ID")
    private String traceId;

    @Schema(description = "是否来自回退（熔断/降级后）")
    private boolean fallback;

    @Schema(description = "业务域 (回显)", example = "CHAT")
    private String bizType;

    public static LangChainResponse ok(String content, String provider, String model) {
        LangChainResponse r = new LangChainResponse();
        r.success = true;
        r.content = content;
        r.provider = provider;
        r.model = model;
        return r;
    }

    public static LangChainResponse fail(String error, String provider) {
        LangChainResponse r = new LangChainResponse();
        r.success = false;
        r.error = error;
        r.provider = provider;
        return r;
    }

    // ---- getters / setters ----

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public List<Map<String, Object>> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<Map<String, Object>> toolCalls) { this.toolCalls = toolCalls; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }

    public String getBizType() { return bizType; }
    public void setBizType(String bizType) { this.bizType = bizType; }
}
