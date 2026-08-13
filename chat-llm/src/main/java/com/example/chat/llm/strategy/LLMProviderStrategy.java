package com.example.chat.llm.strategy;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;

/**
 * LLM 提供商策略接口 — 每个厂商实现自己的适配。
 *
 * <h3>调用方式 (type)</h3>
 * <ul>
 *   <li>{@code rest} — HTTP REST API 调用 (OpenAI 兼容格式)</li>
 *   <li>{@code sdk}  — OpenAI Java SDK 调用</li>
 * </ul>
 */
public interface LLMProviderStrategy {

    /** 调用方式: rest / sdk */
    String INVOKE_TYPE_REST = "rest";
    String INVOKE_TYPE_SDK  = "sdk";

    /** 提供商名称 */
    String name();

    /** 调用方式 (默认 rest) */
    default String invokeType() { return INVOKE_TYPE_REST; }

    /** 是否为 SDK 调用方式 */
    default boolean isSdk() { return INVOKE_TYPE_SDK.equalsIgnoreCase(invokeType()); }

    /** 是否支持该 provider+model 组合 */
    boolean supports(String provider, String model);

    /** 非流式同步调用 */
    LangChainResponse invoke(LangChainRequest request);

    /** 流式调用 (SSE consumer) */
    void invokeStream(LangChainRequest request,
                      java.util.function.Consumer<String> chunkConsumer,
                      Runnable onComplete,
                      java.util.function.Consumer<Throwable> onError);
}
