package com.example.chat.strategy;

import com.example.chat.dto.LLMMessage;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 调用策略接口
 * 不同模型 provider 实现不同的调用策略
 */
public interface LLMStrategy {

    /**
     * 非流式调用
     */
    String invoke(String baseUrl, String apiKey, String model,
                  List<LLMMessage> messages, double temperature) throws Exception;

    /**
     * 流式调用
     */
    String invokeStream(String baseUrl, String apiKey, String model,
                        List<LLMMessage> messages, double temperature,
                        Consumer<String> callback) throws Exception;

    /** 判断该策略是否支持流式调用 */
    default boolean supportsStream() {
        return true;
    }
}
