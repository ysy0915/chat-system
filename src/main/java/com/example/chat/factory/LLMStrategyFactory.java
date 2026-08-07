package com.example.chat.factory;

import com.example.chat.strategy.LLMStrategy;
import com.example.chat.strategy.OpenAICompatStrategy;
import com.example.chat.strategy.DoubaoStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLM 策略工厂
 * 根据 provider 名称返回对应的调用策略
 */
@Component
public class LLMStrategyFactory {

    private final OpenAICompatStrategy openAICompatStrategy;
    private final DoubaoStrategy doubaoStrategy;

    public LLMStrategyFactory(OpenAICompatStrategy openAICompatStrategy,
                               DoubaoStrategy doubaoStrategy) {
        this.openAICompatStrategy = openAICompatStrategy;
        this.doubaoStrategy = doubaoStrategy;
    }

    /**
     * 根据 provider 获取策略
     * - deepseek / qwen / doubao → OpenAICompatStrategy（都支持 /chat/completions）
     * - doubao_responses → DoubaoStrategy（/responses 接口，仅特殊场景）
     */
    public LLMStrategy getStrategy(String provider) {
        if (provider == null) {
            return openAICompatStrategy;
        }
        switch (provider.toLowerCase()) {
            case "doubao_responses":
                return doubaoStrategy;
            case "doubao":
            case "deepseek":
            case "qwen":
            default:
                return openAICompatStrategy;
        }
    }
}
