package com.example.chat.util;

import java.util.Map;

/**
 * 工具执行回调接口。
 *
 * <p>解耦 LlmToolInvoker 与具体工具注册中心：调用方（如 chat-core 的 ToolRegistry）
 * 提供按名查找并执行工具的实现，LlmToolInvoker 只负责 OpenAI function calling 协议。</p>
 */
@FunctionalInterface
public interface LlmToolExecutor {

    /**
     * 按工具名执行工具。
     *
     * @param toolName  工具名（来自 tool_calls.function.name）
     * @param arguments 解析后的参数 map（来自 tool_calls.function.arguments JSON）
     * @return 工具执行结果字符串（回填给 LLM 作上下文）
     */
    String execute(String toolName, Map<String, Object> arguments);
}
