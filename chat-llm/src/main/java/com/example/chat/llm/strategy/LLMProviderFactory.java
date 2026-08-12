package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <h2>LLM 提供商工厂 — SPI 插件化扩展点</h2>
 *
 * <p>新增一个厂商协议只需两步，无需改动注册中心/路由代码：</p>
 * <ol>
 *   <li>实现 {@link LLMProviderStrategy}（调用协议适配）</li>
 *   <li>实现本接口并提供 {@link #create(ProviderConfig, ObjectMapper)} 工厂方法，
 *       注册为 Spring Bean（或调用 {@code LLMProviderStrategyFactory.register}）</li>
 * </ol>
 *
 * <p>{@code type()} 与配置项 {@code llm.providers[].type} 一一对应，
 * 例如内置 {@code rest}（OpenAI 兼容 REST）/ {@code sdk}（OpenAI Java SDK）。</p>
 */
@FunctionalInterface
public interface LLMProviderFactory {

    /** 支持的调用方式类型标识（rest / sdk / 自定义），默认 rest */
    default String type() {
        return LLMProviderStrategy.INVOKE_TYPE_REST;
    }

    /**
     * 根据提供商配置构建策略实例。
     *
     * @param config 单个提供商的 YAML/DB 配置
     * @param mapper 全局 JSON 序列化器（由工厂注入策略实现复用）
     * @return 策略适配器实例
     */
    LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper);
}
