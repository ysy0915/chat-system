package com.example.chat.intent;

/**
 * 意图分类（LLM 语义识别，非关键词规则）
 * <p>
 * 每种意图对应不同的处理策略：模型选择、温度、System Prompt、工具启用等。
 */
public enum IntentCategory {

    /** 日常闲聊 - 用轻量模型、低延迟 */
    GENERAL_CHAT,

    /** 知识问答 - 用强模型、追求准确 */
    KNOWLEDGE_QA,

    /** 代码生成/编程 - 用代码模型 */
    CODE_GENERATION,

    /** 创意写作 - 用高温度模型 */
    CREATIVE_WRITING,

    /** 逻辑推理/分析 - 用最强推理模型 */
    REASONING,

    /** 摘要总结 */
    SUMMARIZATION,

    /** 情感支持/倾诉 - 用角色模型、更高温度 */
    EMOTIONAL_SUPPORT,

    /** 任务执行（计算、天气、搜索等工具调用） */
    TASK_EXECUTION,

    /** 翻译 */
    TRANSLATION,

    /** 无法识别（走默认通用逻辑） */
    UNKNOWN
}
