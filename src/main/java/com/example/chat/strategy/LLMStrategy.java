package com.example.chat.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 调用策略接口
 * 不同模型 provider 实现不同的调用策略
 */
public interface LLMStrategy {

    /**
     * 非流式调用
     * @param baseUrl API 地址
     * @param apiKey API Key
     * @param model 模型名称
     * @param messages 消息列表
     * @param temperature 温度参数
     * @return 完整回答
     */
    String invoke(String baseUrl, String apiKey, String model,
                  List<Map<String, Object>> messages, double temperature) throws Exception;

    /**
     * 流式调用
     * @param baseUrl API 地址
     * @param apiKey API Key
     * @param model 模型名称
     * @param messages 消息列表
     * @param temperature 温度参数
     * @param callback 每个 token 的回调
     * @return 完整回答
     */
    String invokeStream(String baseUrl, String apiKey, String model,
                        List<Map<String, Object>> messages, double temperature,
                        Consumer<String> callback) throws Exception;

    /**
     * 判断该策略是否支持流式调用
     */
    default boolean supportsStream() {
        return true;
    }
}
