package com.example.chat.agent.tool;

import java.util.Map;

/**
 * 工具接口
 * 所有可被 AI 调用的工具都实现此接口
 */
public interface Tool {

    /** 工具名（如 "weather"、"calculator"），需唯一 */
    String getName();

    /** 工具描述（给 AI 看，用于判断何时调用该工具） */
    String getDescription();

    /** 参数 JSON Schema（给 AI 看，描述调用参数结构） */
    String getParameters();

    /**
     * 执行工具
     * @param params 参数 map（由 LLM 的 tool_calls.arguments JSON 解析而来）
     * @return 工具执行结果字符串（会被回填到 messages 给 LLM 作为上下文）
     */
    String execute(Map<String, Object> params);
}
