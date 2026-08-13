package com.example.chat.llm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>LLM 全局配置</h2>
 *
 * <p>从 application.yml 的 llm.* 前缀读取配置。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LLMConfig {

    /** 单次调用超时 (秒) */
    private int invokeTimeoutSeconds = 120;

    /** 最大重试次数 */
    private int maxRetries = 2;

    /** 重试退避 (毫秒) */
    private long retryBackoffMillis = 1000;

    /** 提供商列表 */
    private List<ProviderConfig> providers = new ArrayList<>();

    // ── ProviderConfig ────────────────────────────────────

    public static class ProviderConfig {
        /** 调用方式: rest (HTTP REST API) / sdk (OpenAI SDK) */
        private String type = "rest";
        private String name;
        private String baseUrl;
        private String apiKey;
        /** chat/completions 接口路径 — 默认 /v1/chat/completions，兼容不同厂商 */
        private String path = "/v1/chat/completions";
        private List<String> models = new ArrayList<>();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public List<String> getModels() { return models; }
        public void setModels(List<String> models) { this.models = models != null ? models : new ArrayList<>(); }

        /** 是否为 OpenAI SDK 调用方式 */
        public boolean isSdk() { return "sdk".equalsIgnoreCase(type); }

        /** 是否为 REST API 调用方式 */
        public boolean isRest() { return type == null || "rest".equalsIgnoreCase(type); }
    }

    // ── Getters / Setters ─────────────────────────────────

    public int getInvokeTimeoutSeconds() { return invokeTimeoutSeconds; }
    public void setInvokeTimeoutSeconds(int invokeTimeoutSeconds) { this.invokeTimeoutSeconds = invokeTimeoutSeconds; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getRetryBackoffMillis() { return retryBackoffMillis; }
    public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }

    public List<ProviderConfig> getProviders() { return providers; }
    public void setProviders(List<ProviderConfig> providers) { this.providers = providers != null ? providers : new ArrayList<>(); }
}
