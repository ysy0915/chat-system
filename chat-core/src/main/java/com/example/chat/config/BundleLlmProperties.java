package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM Bundle (chat-llm 统一网关) 接入配置。
 *
 * <p>启用后 chat-core 的模型调用优先走 chat-llm 模块
 * （自带 provider 路由、熔断/重试/限流、故障转移、bizType 流控打标）。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.llm.bundle")
public class BundleLlmProperties {

    /** 是否启用 bundle 模式：模型调用优先走 chat-llm */
    private boolean enabled;

    /** chat-llm 服务地址 */
    private String baseUrl = "http://127.0.0.1:9095";

    /** 非流式调用超时 */
    private Duration timeout = Duration.ofSeconds(120);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
