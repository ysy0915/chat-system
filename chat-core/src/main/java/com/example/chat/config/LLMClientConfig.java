package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM Client 运行时配置（支持 Nacos 动态刷新）
 *
 * 通过 Nacos 修改配置后无需重启服务，配置自动生效。
 * 每个场景的运行参数独立可调。
 *
 * Nacos 配置示例 (Data ID: chat-core, Group: DEFAULT_GROUP):
 * <pre>
 * app.llm.client.retry.max-attempts=3
 * app.llm.client.retry.backoff-ms=500
 * app.llm.client.timeout.connect-seconds=10
 * app.llm.client.timeout.read-seconds=60
 * app.llm.client.scenes.chat.max-tokens=4096
 * app.llm.client.scenes.chat.temperature=0.7
 * app.llm.client.scenes.debate.max-tokens=2048
 * app.llm.client.scenes.debate.temperature=0.9
 * app.llm.client.scenes.summary.max-tokens=1024
 * app.llm.client.scenes.summary.temperature=0.3
 * </pre>
 */
@Component
@RefreshScope
@ConfigurationProperties(prefix = "app.llm.client")
public class LLMClientConfig {

    /** 重试配置 */
    private Retry retry = new Retry();

    /** 超时配置 */
    private Timeout timeout = new Timeout();

    /** 各场景运行参数 */
    private Map<String, SceneConfig> scenes = new HashMap<>();

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public Timeout getTimeout() { return timeout; }
    public void setTimeout(Timeout timeout) { this.timeout = timeout; }

    public Map<String, SceneConfig> getScenes() { return scenes; }
    public void setScenes(Map<String, SceneConfig> scenes) { this.scenes = scenes; }

    /**
     * 获取场景配置，无特殊配置时返回默认值
     */
    public SceneConfig getScene(String scene) {
        return scenes.getOrDefault(scene, SceneConfig.DEFAULTS);
    }

    // ========== 内部类 ==========

    public static class Retry {
        private int maxAttempts = 3;
        private long backoffMs = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getBackoffMs() { return backoffMs; }
        public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }
    }

    public static class Timeout {
        /** 连接超时（秒） */
        private int connectSeconds = 10;
        /** 读取超时（秒） */
        private int readSeconds = 60;

        public int getConnectSeconds() { return connectSeconds; }
        public void setConnectSeconds(int connectSeconds) { this.connectSeconds = connectSeconds; }
        public int getReadSeconds() { return readSeconds; }
        public void setReadSeconds(int readSeconds) { this.readSeconds = readSeconds; }

        public Duration connectDuration() { return Duration.ofSeconds(connectSeconds); }
        public Duration readDuration() { return Duration.ofSeconds(readSeconds); }
    }

    public static class SceneConfig {
        /** 默认配置 */
        public static final SceneConfig DEFAULTS = new SceneConfig();

        private int maxTokens = 4096;
        private double temperature = 0.7;
        private double topP = 1.0;
        private String persona = "";      // 可选的场景人设提示词
        private boolean enableTools = false;

        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public double getTopP() { return topP; }
        public void setTopP(double topP) { this.topP = topP; }
        public String getPersona() { return persona; }
        public void setPersona(String persona) { this.persona = persona; }
        public boolean isEnableTools() { return enableTools; }
        public void setEnableTools(boolean enableTools) { this.enableTools = enableTools; }
    }
}
