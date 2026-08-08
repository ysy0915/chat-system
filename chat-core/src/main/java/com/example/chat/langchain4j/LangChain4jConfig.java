package com.example.chat.langchain4j;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 模型配置
 *
 * 提供 ChatLanguageModel Bean，供 AiServices 使用
 * 通过 app.langchain4j.enabled=true 开启
 *
 * 复用现有千问 API Key 和 baseUrl（OpenAI 兼容接口）
 */
@Configuration
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    @Value("${app.langchain4j.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${app.langchain4j.api-key:}")
    private String apiKey;

    @Value("${app.langchain4j.model:qwen-plus}")
    private String model;

    @Value("${app.langchain4j.temperature:0.85}")
    private double temperature;

    @Value("${app.langchain4j.timeout:60}")
    private int timeout;

    /**
     * 非流式 ChatLanguageModel
     * 用于 AiServices 的简单对话场景
     */
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        log.info("[LangChain4j] 初始化 ChatLanguageModel baseUrl={} model={}", baseUrl, model);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .temperature(temperature)
                .timeout(java.time.Duration.ofSeconds(timeout))
                .build();
    }
}
