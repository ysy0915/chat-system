package com.example.chat.intent;

import org.springframework.stereotype.Component;

/**
 * 意图 → LLM 参数映射
 * <p>
 * 每种意图对应不同的 temperature、System Prompt 偏好、工具启用策略。
 * 当前版本聚焦 temperature 动态调整和 LLM-invoker 参数透传；
 * 后续可扩展模型路由/提示词注入等。
 */
@Component
public class IntentRoutingHelper {

    /**
     * 根据意图获取推荐 temperature
     */
    public double temperatureFor(IntentCategory category) {
        return switch (category) {
            case CREATIVE_WRITING   -> 0.95;   // 高创意
            case EMOTIONAL_SUPPORT  -> 0.85;   // 高温度增加共情多样性
            case GENERAL_CHAT       -> 0.7;    // 默认平衡
            case KNOWLEDGE_QA       -> 0.3;    // 低温度保证准确
            case CODE_GENERATION    -> 0.2;    // 低温度保证代码稳定
            case REASONING          -> 0.2;    // 逻辑推理需稳定
            case SUMMARIZATION      -> 0.1;    // 极低温度
            case TRANSLATION        -> 0.1;    // 极低温度
            case TASK_EXECUTION     -> 0.3;    // 任务执行需准确
            case UNKNOWN            -> 0.7;    // 默认
        };
    }

    /**
     * 是否需要启用工具调用（Tools/Function Calling）
     */
    public boolean shouldEnableTools(IntentCategory category) {
        return category == IntentCategory.TASK_EXECUTION
                || category == IntentCategory.CODE_GENERATION;
    }

    /**
     * 获取意图的日志友好名称
     */
    public String label(IntentCategory category) {
        return switch (category) {
            case GENERAL_CHAT       -> "闲聊";
            case KNOWLEDGE_QA       -> "问答";
            case CODE_GENERATION    -> "代码";
            case CREATIVE_WRITING   -> "创作";
            case REASONING          -> "推理";
            case SUMMARIZATION      -> "摘要";
            case EMOTIONAL_SUPPORT  -> "情感";
            case TASK_EXECUTION     -> "任务";
            case TRANSLATION        -> "翻译";
            case UNKNOWN            -> "未知";
        };
    }
}
