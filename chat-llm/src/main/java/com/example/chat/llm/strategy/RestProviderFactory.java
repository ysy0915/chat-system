package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * <h2>REST 调用方式策略工厂（内置默认）</h2>
 *
 * <p>构造 {@link OpenAICompatProvider} —— OpenAI 兼容 HTTP 协议实现，
 * 适用于 DeepSeek / 千问 等兼容 {@code /v1/chat/completions} 的厂商。</p>
 */
@Component
public class RestProviderFactory implements LLMProviderFactory {

    @Override
    public String type() {
        return LLMProviderStrategy.INVOKE_TYPE_REST;
    }

    @Override
    public LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper) {
        return new OpenAICompatProvider(config, mapper);
    }
}
