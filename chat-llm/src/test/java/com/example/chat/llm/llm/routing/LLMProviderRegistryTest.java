package com.example.chat.llm.llm.routing;

import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.strategy.LLMProviderStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * LLMProviderRegistry 测试 — 多 Provider 注册、路由解析、默认 Provider 选择。
 */
class LLMProviderRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============ 路由解析 ============

    @Test
    void shouldResolveExactProviderAndModel() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat", "deepseek-reasoner")));

        LLMProviderRegistry.RouteResult r = registry.resolve("deepseek", "deepseek-reasoner");

        assertTrue(r.found());
        assertEquals("deepseek", r.providerName());
        assertEquals("deepseek-reasoner", r.modelName());
        assertNotNull(r.strategy());
    }

    @Test
    void shouldResolveDefaultModelWhenModelUnknown() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat", "deepseek-reasoner")));

        LLMProviderRegistry.RouteResult r = registry.resolve("deepseek", "unknown-model");

        assertTrue(r.found());
        assertEquals("deepseek-chat", r.modelName());
    }

    @Test
    void shouldResolveFirstModelWhenModelBlank() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat", "deepseek-reasoner")));

        LLMProviderRegistry.RouteResult r = registry.resolve("deepseek", null);

        assertTrue(r.found());
        assertEquals("deepseek-chat", r.modelName());
    }

    @Test
    void shouldReturnNotFoundForUnknownProvider() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat")));

        LLMProviderRegistry.RouteResult r = registry.resolve("unknown-provider", null);

        assertFalse(r.found());
        assertTrue(r.error().contains("未知提供商"));
    }

    @Test
    void shouldResolveDefaultProviderWhenProviderBlank() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat")));

        LLMProviderRegistry.RouteResult r = registry.resolve(null, null);

        assertTrue(r.found());
        assertEquals("deepseek", r.providerName());
    }

    @Test
    void shouldReturnNotFoundWhenNoProviderAvailable() {
        LLMProviderRegistry registry = new LLMProviderRegistry(new LLMConfig(), mapper);

        LLMProviderRegistry.RouteResult r = registry.resolve(null, null);

        assertFalse(r.found());
    }

    // ============ init 过滤 ============

    @Test
    void shouldSkipProviderWithoutApiKey() {
        LLMConfig config = new LLMConfig();
        config.setProviders(List.of(provider("no-key", "", List.of("m1"))));
        LLMProviderRegistry registry = new LLMProviderRegistry(config, mapper);

        assertTrue(registry.listProviderNames().isEmpty());
    }

    // ============ 动态注册 ============

    @Test
    void shouldDynamicallyRegisterAndResolve() {
        LLMProviderRegistry registry = new LLMProviderRegistry(new LLMConfig(), mapper);
        ProviderRoute p = new ProviderRoute();
        p.setName("custom");
        p.setApiKey("sk-custom");
        p.setEnabled(true);
        ModelRoute m = new ModelRoute();
        m.setName("custom-model");
        m.setEnabled(true);
        m.setDefault(true);
        p.addModel(m);
        LLMProviderStrategy strategy = mock(LLMProviderStrategy.class);
        registry.register(p, strategy);

        LLMProviderRegistry.RouteResult r = registry.resolve("custom", "custom-model");

        assertTrue(r.found());
        assertSame(strategy, r.strategy());
        assertEquals("sk-custom", r.apiKey());
    }

    @Test
    void shouldUnregisterProvider() {
        LLMProviderRegistry registry = new LLMProviderRegistry(new LLMConfig(), mapper);
        ProviderRoute p = new ProviderRoute();
        p.setName("temp");
        p.setApiKey("sk");
        p.setEnabled(true);
        ModelRoute m = new ModelRoute();
        m.setName("temp-model");
        m.setEnabled(true);
        m.setDefault(true);
        p.addModel(m);
        registry.register(p, mock(LLMProviderStrategy.class));
        assertTrue(registry.resolve("temp", null).found());

        registry.unregister("temp");

        assertFalse(registry.resolve("temp", null).found());
    }

    // ============ 列表 / 查询 ============

    @Test
    void shouldListProviderNames() {
        LLMProviderRegistry registry = buildRegistry(
                provider("deepseek", "sk-1", List.of("deepseek-chat")),
                provider("qwen", "sk-2", List.of("qwen-plus")));

        List<String> names = registry.listProviderNames();

        assertEquals(2, names.size());
        assertTrue(names.contains("deepseek"));
        assertTrue(names.contains("qwen"));
    }

    @Test
    void shouldGetProviderInfo() {
        LLMProviderRegistry registry = buildRegistry(provider("deepseek", "sk-1",
                List.of("deepseek-chat")));

        ProviderRoute p = registry.getProvider("DEEPSEEK");

        assertNotNull(p);
        assertEquals("deepseek", p.getName());
        assertEquals(1, p.getModels().size());
        assertNull(registry.getProvider("nope"));
    }

    // ============ helpers ============

    private LLMProviderRegistry buildRegistry(LLMConfig.ProviderConfig... pcs) {
        LLMConfig config = new LLMConfig();
        config.setProviders(List.of(pcs));
        LLMProviderRegistry registry = new LLMProviderRegistry(config, mapper);
        // @PostConstruct 在纯单元测试中不会自动触发，需手动初始化
        registry.init();
        return registry;
    }

    private LLMConfig.ProviderConfig provider(String name, String apiKey, List<String> models) {
        LLMConfig.ProviderConfig pc = new LLMConfig.ProviderConfig();
        pc.setName(name);
        pc.setBaseUrl("https://" + name + ".example.com");
        pc.setApiKey(apiKey);
        pc.setModels(models);
        return pc;
    }
}
