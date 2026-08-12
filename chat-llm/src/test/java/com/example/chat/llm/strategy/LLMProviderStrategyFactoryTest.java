package com.example.chat.llm.strategy;

import com.example.chat.llm.config.LLMConfig.ProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLMProviderStrategyFactory 测试 — 内置 rest/sdk 策略、SPI 收集、动态注册、未知类型回退。
 */
class LLMProviderStrategyFactoryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ProviderConfig config(String name, String type) {
        ProviderConfig pc = new ProviderConfig();
        pc.setName(name);
        pc.setType(type);
        pc.setBaseUrl("https://" + name + ".example.com");
        pc.setApiKey("sk-test");
        pc.setModels(List.of(name + "-chat"));
        return pc;
    }

    // ============ 内置默认策略 ============

    @Test
    void shouldCreateRestStrategyByDefault() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);

        LLMProviderStrategy strategy = factory.create(config("deepseek", null));

        assertInstanceOf(OpenAICompatProvider.class, strategy);
        assertFalse(strategy.isSdk());
        assertEquals("deepseek", strategy.name());
    }

    @Test
    void shouldCreateRestStrategyForRestType() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);

        LLMProviderStrategy strategy = factory.create(config("qwen", "rest"));

        assertInstanceOf(OpenAICompatProvider.class, strategy);
        assertEquals("rest", strategy.invokeType());
    }

    @Test
    void shouldCreateSdkStrategyForSdkType() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);

        LLMProviderStrategy strategy = factory.create(config("openai", "sdk"));

        assertInstanceOf(OpenAISdkProvider.class, strategy);
        assertTrue(strategy.isSdk());
    }

    // ============ SPI 收集（Spring 环境等价路径） ============

    @Test
    void shouldCollectSpiFactories() {
        // 模拟 Spring 收集到内置 rest/sdk 工厂 + 自定义 SPI 实现
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper,
                List.of(new RestProviderFactory(), new SdkProviderFactory(), new MockProviderFactory()));

        LLMProviderStrategy sdk = factory.create(config("openai", "sdk"));
        LLMProviderStrategy mock = factory.create(config("mock", "mock-protocol"));

        assertInstanceOf(OpenAISdkProvider.class, sdk);
        assertInstanceOf(MockProviderStrategy.class, mock);
        assertTrue(factory.supportedTypes().containsAll(
                List.of("rest", "sdk", "mock-protocol")));
    }

    // ============ 动态注册 ============

    @Test
    void shouldRegisterCustomFactoryDynamically() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);
        assertFalse(factory.supportedTypes().contains("mock-protocol"));

        factory.register("mock-protocol", (pc, om) -> new MockProviderStrategy(pc));

        assertTrue(factory.supportedTypes().contains("mock-protocol"));
        LLMProviderStrategy strategy = factory.create(config("mock", "mock-protocol"));
        assertInstanceOf(MockProviderStrategy.class, strategy);
        assertEquals("mock", strategy.name());
    }

    @Test
    void shouldIgnoreInvalidRegistration() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);

        factory.register("", (pc, om) -> new MockProviderStrategy(pc));
        factory.register(null, (pc, om) -> new MockProviderStrategy(pc));

        assertFalse(factory.supportedTypes().contains(""));
        assertEquals(2, factory.supportedTypes().size()); // 仅 rest / sdk
    }

    // ============ 容错回退 ============

    @Test
    void shouldFallbackToRestForUnknownType() {
        LLMProviderStrategyFactory factory = new LLMProviderStrategyFactory(mapper);

        LLMProviderStrategy strategy = factory.create(config("unknown", "anthropic-native"));

        // 未知类型回退 rest，保证路由不中断
        assertInstanceOf(OpenAICompatProvider.class, strategy);
        assertEquals("rest", strategy.invokeType());
    }

    // ============ 自定义 SPI 样例 ============

    /** 模拟第三方厂商 SPI：mock 协议 */
    private static final class MockProviderFactory implements LLMProviderFactory {
        @Override
        public String type() {
            return "mock-protocol";
        }

        @Override
        public LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper) {
            return new MockProviderStrategy(config);
        }
    }

    /** 模拟第三方厂商策略实现 */
    private static final class MockProviderStrategy implements LLMProviderStrategy {
        private final ProviderConfig config;

        MockProviderStrategy(ProviderConfig config) {
            this.config = config;
        }

        @Override
        public String name() {
            return config.getName();
        }

        @Override
        public String invokeType() {
            return "mock-protocol";
        }

        @Override
        public boolean supports(String provider, String model) {
            return config.getName().equalsIgnoreCase(provider);
        }

        @Override
        public com.example.chat.dto.LangChainResponse invoke(com.example.chat.dto.LangChainRequest request) {
            return com.example.chat.dto.LangChainResponse.fail("mock 未实现", request.getProvider());
        }

        @Override
        public void invokeStream(com.example.chat.dto.LangChainRequest request,
                                 java.util.function.Consumer<String> chunkConsumer,
                                 Runnable onComplete,
                                 java.util.function.Consumer<Throwable> onError) {
            onError.accept(new UnsupportedOperationException("mock 未实现"));
        }
    }
}
