package com.example.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 基础配置属性（统一管理 @Value 注入）
 *
 * ChatProcessor、SummaryService、HistorySummaryService、
 * KnowledgeGraphService、TreeHoleService 共享此配置
 */
@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmConfigProperties {

    /** 默认 Base URL */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 默认 API Key */
    private String apiKey = "";

    /** 默认模型名称 */
    private String model = "qwen-plus";

    /** 默认 Provider（如 qwen、doubao、openai 等） */
    private String provider = "qwen";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
