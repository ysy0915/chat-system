package com.example.chat.strategy;

import com.example.chat.dto.LLMMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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

    /**
     * 异步非流式调用（基于 NIO，不阻塞调用线程）
     * 默认实现：通过 CompletableFuture.supplyAsync 包装同步 invoke。
     * 实现类应 override 此方法，使用 sendAsync 实现真正的异步 IO。
     */
    default CompletableFuture<String> invokeAsync(String baseUrl, String apiKey, String model,
                                                   List<LLMMessage> messages, double temperature) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return invoke(baseUrl, apiKey, model, messages, temperature);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 异步流式调用
     * 默认实现：包装同步 invokeStream。
     */
    default CompletableFuture<String> invokeStreamAsync(String baseUrl, String apiKey, String model,
                                                         List<LLMMessage> messages, double temperature,
                                                         Consumer<String> callback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return invokeStream(baseUrl, apiKey, model, messages, temperature, callback);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /** 判断该策略是否支持流式调用 */
    default boolean supportsStream() {
        return true;
    }
}
