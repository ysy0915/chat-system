package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>LLM 策略工厂 — 消除注册中心硬编码分支</h2>
 *
 * <p>注册中心不再 {@code if (isSdk())} 手工 new，而是按 {@code type} 查字典：
 * <pre>
 *   invokeType (rest / sdk / 自定义)  ──►  LLMProviderFactory  ──►  LLMProviderStrategy
 * </pre></p>
 *
 * <h3>扩展方式（SPI 插件化）</h3>
 * <ul>
 *   <li><b>Spring Bean 自动收集</b>：实现 {@link LLMProviderFactory} 并标注 {@code @Component}，
 *       构造器自动收集进工厂字典（新增厂商协议零改动注册中心）</li>
 *   <li><b>代码动态注册</b>：调用 {@link #register(LLMProviderFactory)} / {@link #register(String, LLMProviderFactory)}</li>
 * </ul>
 *
 * <h3>容错</h3>
 * <ul>
 *   <li>配置了未注册的 {@code type} → 回退 {@code rest} 并告警日志（保证路由不中断）</li>
 *   <li>{@code type} 为空/空白 → 按 {@code rest} 处理（与 {@code ProviderConfig.isRest()} 语义一致）</li>
 * </ul>
 */
@Component
public class LLMProviderStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(LLMProviderStrategyFactory.class);

    /** invokeType(小写) → 策略工厂 */
    private final Map<String, LLMProviderFactory> factories = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    /**
     * Spring 构造器：自动收集所有 {@link LLMProviderFactory} Bean（内置 rest/sdk + 自定义 SPI）。
     * 注：多构造器场景必须标注 @Autowired，否则 Spring 无法选择默认构造器。
     */
    @Autowired
    public LLMProviderStrategyFactory(ObjectMapper mapper, List<LLMProviderFactory> spiFactories) {
        this.mapper = mapper;
        // 1) 内置默认工厂兜底
        register(new RestProviderFactory());
        register(new SdkProviderFactory());
        // 2) SPI 收集（同名 type 由自定义实现覆盖内置默认）
        if (spiFactories != null) {
            for (LLMProviderFactory f : spiFactories) {
                register(f);
            }
        }
    }

    /**
     * 便捷构造：仅内置 rest/sdk 工厂（单元测试 / 非 Spring 环境使用）。
     */
    public LLMProviderStrategyFactory(ObjectMapper mapper) {
        this(mapper, List.of());
    }

    /**
     * 动态注册自定义策略工厂（SPI 扩展点）。
     */
    public void register(LLMProviderFactory factory) {
        if (factory == null) return;
        register(factory.type(), factory);
    }

    /**
     * 按调用方式类型动态注册自定义策略工厂。
     *
     * @param type    config 中 {@code llm.providers[].type} 取值（不区分大小写）
     * @param factory 策略工厂实现
     */
    public void register(String type, LLMProviderFactory factory) {
        if (type == null || type.isBlank() || factory == null) {
            log.warn("[LLMStrategyFactory] 忽略非法注册 type={} factory={}",
                    type, factory != null ? factory.getClass().getSimpleName() : null);
            return;
        }
        String key = type.toLowerCase();
        LLMProviderFactory old = factories.put(key, factory);
        log.info("[LLMStrategyFactory] 注册策略工厂 type={} class={} {}",
                key, factory.getClass().getSimpleName(),
                old != null ? "(覆盖 " + old.getClass().getSimpleName() + ")" : "");
    }

    /**
     * 按配置创建策略实例；未知类型回退 {@code rest}。
     */
    public LLMProviderStrategy create(ProviderConfig config) {
        String type = config.getType() == null || config.getType().isBlank()
                ? LLMProviderStrategy.INVOKE_TYPE_REST
                : config.getType().toLowerCase();
        LLMProviderFactory factory = factories.get(type);
        if (factory == null) {
            log.warn("[LLMStrategyFactory] 提供商 {} 配置了未注册的调用方式 '{}'，回退 rest",
                    config.getName(), type);
            factory = factories.get(LLMProviderStrategy.INVOKE_TYPE_REST);
        }
        return factory.create(config, mapper);
    }

    /**
     * 当前已注册的调用方式类型集合（供管理面展示 / 配置校验）。
     */
    public Set<String> supportedTypes() {
        return new TreeSet<>(factories.keySet());
    }
}
