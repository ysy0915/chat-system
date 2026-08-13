package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * <h2>SDK 调用方式策略工厂（内置默认）</h2>
 *
 * <p>构造 {@link OpenAISdkProvider} —— OpenAI Java SDK 调用实现，
 * 使用官方 {@code com.openai:openai-java} SDK（连接池复用 / 自动重试）。</p>
 */
@Component
public class SdkProviderFactory implements LLMProviderFactory {

    @Override
    public String type() {
        return LLMProviderStrategy.INVOKE_TYPE_SDK;
    }

    @Override
    public LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper) {
        return new OpenAISdkProvider(config, mapper);
    }
}
