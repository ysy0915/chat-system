package com.example.chat.factory;

import com.example.chat.router.TaskType;
import com.example.chat.strategy.LLMStrategy;
import com.example.chat.strategy.OpenAICompatStrategy;
import com.example.chat.strategy.DoubaoStrategy;
import org.springframework.stereotype.Component;

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

    /**
     * 根据任务类型和 provider 获取策略
     * - VISION 任务：确保使用支持视觉的策略（OpenAICompatStrategy 支持 image_url 多模态消息）
     * - 其他任务类型：沿用现有 provider 路由
     *
     * @param taskType 任务类型（可为 null，则等价于 getStrategy(provider)）
     * @param provider 模型 provider
     * @return 对应的 LLMStrategy
     */
    public LLMStrategy getStrategyForTask(TaskType taskType, String provider) {
        // VISION 任务必须走 OpenAICompatStrategy（/chat/completions 支持 image_url 多模态）
        // DoubaoStrategy 的 /responses 接口不支持多模态图片
        if (taskType == TaskType.VISION) {
            return openAICompatStrategy;
        }
        // 其他任务沿用 provider 路由
        return getStrategy(provider);
    }
}
