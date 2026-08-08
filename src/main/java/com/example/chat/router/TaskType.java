package com.example.chat.router;

/**
 * 任务类型枚举
 * 用于动态模型路由，不同任务类型匹配不同的最优模型策略
 */
public enum TaskType {
    /** 简单闲聊（用便宜模型） */
    SIMPLE_CHAT,
    /** 复杂推理（用强模型） */
    COMPLEX_REASONING,
    /** 图片理解（用视觉模型） */
    VISION,
    /** 摘要生成（用轻量模型） */
    SUMMARIZATION,
    /** 创意写作（用高温度模型） */
    CREATIVE,
    /** 代码生成（用代码模型） */
    CODE,
    /** 情感对话（用角色模型） */
    EMOTIONAL,
    /** 辩论（用强模型） */
    DEBATE
}
