package com.example.chat.service;

import com.example.chat.config.LLMClientConfig;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 统一客户端（Fluent Builder）
 *
 * 提供按场景预设配置的 Builder API，减少 LLMInvoker 的样板代码。
 * 非流式写入 CallTrace 供可观测性面板使用，流式写入可分割 CallTrace 便于追踪首字延迟。
 *
 * <pre>{@code
 * // 简洁调用
 * llmClient.forConfig(modelConfig)
 *     .scene("chat")
 *     .messages(messages)
 *     .execute();
 *
 * // 流式调用
 * llmClient.forConfig(modelConfig)
 *     .scene("debate")
 *     .temperature(0.9)
 *     .stream(callback)
 *     .execute();
 *
 * // 自定义场景配置
 * llmClient.forConfig(modelConfig)
 *     .scene("summary")
 *     .maxTokens(512)
 *     .temperature(0.3)
 *     .execute();
 * }</pre>
 */
@Component
@ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);

    private final LLMInvoker llmInvoker;
    private final LLMClientConfig clientConfig;

    public LLMClient(LLMInvoker llmInvoker, LLMClientConfig clientConfig) {
        this.llmInvoker = llmInvoker;
        this.clientConfig = clientConfig;
    }

    /**
     * 为指定 ModelConfig 创建 Builder
     */
    public Builder forConfig(ModelConfig config) {
        return new Builder(config);
    }

    /**
     * LLM 调用构造器（Fluent API）
     */
    public class Builder {
        final ModelConfig config;
        String scene = "chat";
        List<LLMMessage> messages = new ArrayList<>();
        double temperature = -1;       // -1 表示使用场景默认值
        int maxTokens = -1;
        String systemPrompt;
        String userInput;
        Consumer<String> streamCallback;
        String defaultBaseUrl;
        String defaultApiKey;

        Builder(ModelConfig config) {
            this.config = config;
        }

        /** 设置调用场景（chat / debate / treehole / auto / summary） */
        public Builder scene(String scene) {
            this.scene = scene;
            return this;
        }

        /** 设置消息列表 */
        public Builder messages(List<LLMMessage> messages) {
            this.messages = messages;
            return this;
        }

        /** 追加一条消息 */
        public Builder addMessage(LLMMessage message) {
            this.messages.add(message);
            return this;
        }

        /** 设置系统提示词 */
        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        /** 设置用户输入（简化为单条 user message） */
        public Builder userInput(String userInput) {
            this.userInput = userInput;
            return this;
        }

        /** 覆盖场景默认温度 */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /** 覆盖场景默认 max_tokens */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** 使用流式调用 */
        public Builder stream(Consumer<String> callback) {
            this.streamCallback = callback;
            return this;
        }

        /** 覆盖默认 BaseUrl */
        public Builder defaultBaseUrl(String baseUrl) {
            this.defaultBaseUrl = baseUrl;
            return this;
        }

        /** 覆盖默认 API Key */
        public Builder defaultApiKey(String apiKey) {
            this.defaultApiKey = apiKey;
            return this;
        }

        /**
         * 执行调用
         * @return LLM 完整回答文本
         * @throws LLMCallException 调用失败时抛出
         */
        public String execute() {
            try {
                // 1. 解析场景温度
                double effectiveTemp = (temperature >= 0)
                        ? temperature
                        : clientConfig.getScene(scene).getTemperature();

                // 2. 构建消息列表
                List<LLMMessage> builtMessages = buildMessages();
                if (builtMessages.isEmpty()) {
                    throw new IllegalArgumentException("LLMClient: messages 不能为空");
                }

                // 3. 执行调用
                if (streamCallback != null) {
                    return llmInvoker.invokeStream(
                            config, builtMessages, effectiveTemp, scene,
                            resolveBaseUrl(), resolveApiKey(), streamCallback);
                } else {
                    return llmInvoker.invoke(
                            config, builtMessages, effectiveTemp, scene,
                            resolveBaseUrl(), resolveApiKey());
                }
            } catch (LLMCallException le) {
                throw le;
            } catch (Exception e) {
                // 其余异常包装为 LLMCallException
                throw new LLMCallException("LLMClient 调用失败 scene=" + scene, e);
            }
        }

        /**
         * 执行流式调用（等价于 .stream(callback).execute()）
         */
        public String executeStream(Consumer<String> callback) {
            this.streamCallback = callback;
            return execute();
        }

        private List<LLMMessage> buildMessages() {
            List<LLMMessage> built = new ArrayList<>();

            // 系统提示词
            String effectiveSystem = systemPrompt != null ? systemPrompt
                    : clientConfig.getScene(scene).getPersona();
            if (effectiveSystem != null && !effectiveSystem.isBlank()) {
                built.add(LLMMessage.system(effectiveSystem));
            }

            // 添加已有的消息
            built.addAll(messages);

            // 单条 user input
            if (userInput != null && !userInput.isBlank()) {
                built.add(LLMMessage.user(userInput));
            }

            return built;
        }

        private String resolveBaseUrl() {
            return defaultBaseUrl != null ? defaultBaseUrl : "";
        }

        private String resolveApiKey() {
            return defaultApiKey != null ? defaultApiKey : "";
        }
    }
}
