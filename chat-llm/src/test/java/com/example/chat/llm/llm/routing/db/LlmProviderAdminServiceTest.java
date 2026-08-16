package com.example.chat.llm.llm.routing.db;

import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.llm.routing.LLMProviderRegistry;
import com.example.chat.llm.strategy.LLMProviderStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LlmProviderAdminService} 关键语义测试 — 聚焦「DB 覆盖 YAML + 定时刷新」。
 *
 * <p>不依赖真实 DB（mock {@link LlmRoutingRepository}），使用真实的
 * {@link LLMProviderRegistry} + {@link LLMProviderStrategyFactory}，验证：
 * 启动加载、定时刷新覆盖 key、刷新失败保留旧路由、跳过禁用/缺 key 提供商。</p>
 */
class LlmProviderAdminServiceTest {

    private LlmRoutingRepository repo;
    private LLMProviderRegistry registry;
    private LlmProviderAdminService service;

    @BeforeEach
    void setUp() {
        repo = mock(LlmRoutingRepository.class);

        // YAML 兜底：qwen 旧 key（模拟 .env 失效 key）
        LLMConfig llmConfig = new LLMConfig();
        LLMConfig.ProviderConfig qwenYaml = new LLMConfig.ProviderConfig();
        qwenYaml.setName("qwen");
        qwenYaml.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        qwenYaml.setApiKey("sk-env-STALE");
        qwenYaml.setModels(List.of("qwen-plus"));
        llmConfig.setProviders(List.of(qwenYaml));

        ObjectMapper mapper = new ObjectMapper();
        registry = new LLMProviderRegistry(llmConfig, mapper);
        registry.loadFromYaml(); // 模拟 @PostConstruct

        LLMProviderStrategyFactory strategyFactory = new LLMProviderStrategyFactory(mapper);
        service = new LlmProviderAdminService(repo, registry, strategyFactory);
    }

    private LlmProviderRow provider(long id, String name, boolean enabled) {
        LlmProviderRow row = new LlmProviderRow();
        row.setId(id);
        row.setProviderName(name);
        row.setBaseUrl("https://example.com/v1");
        row.setAuthType("api_key");
        row.setInvokeType("rest");
        row.setEnabled(enabled);
        row.setIsDefault(false);
        row.setPriority(0);
        return row;
    }

    private List<Map<String, Object>> props(long providerId, String apiKey) {
        return List.of(Map.of(
                "propKey", "api_key",
                "propValue", apiKey,
                "propType", "SECRET"));
    }

    @Test
    void loadDbProviders_db有效key覆盖YAML旧key() {
        // DB 里有 qwen 的新 key
        when(repo.listProviders()).thenReturn(List.of(provider(23, "qwen", true)));
        when(repo.listProps(23L)).thenReturn(props(23, "sk-db-NEW"));

        int n = service.loadDbProviders();

        assertEquals(1, n);
        // 注册中心的 qwen 现在应是 DB 的新 key
        assertNotNull(registry.getProvider("qwen"));
        assertEquals("sk-db-NEW", registry.getProvider("qwen").getApiKey());
    }

    @Test
    void scheduledRefresh_key变更后自动覆盖() {
        // 第一轮：DB key = sk-db-v1
        when(repo.listProviders()).thenReturn(List.of(provider(23, "qwen", true)));
        when(repo.listProps(23L)).thenReturn(props(23, "sk-db-v1"));
        service.scheduledRefresh();
        assertEquals("sk-db-v1", registry.getProvider("qwen").getApiKey());

        // 模拟运维改 DB key，下一轮定时刷新应覆盖为新值
        when(repo.listProps(23L)).thenReturn(props(23, "sk-db-v2"));
        service.scheduledRefresh();
        assertEquals("sk-db-v2", registry.getProvider("qwen").getApiKey());
    }

    @Test
    void scheduledRefresh_db故障时保留旧路由() {
        // 先成功加载一次
        when(repo.listProviders()).thenReturn(List.of(provider(23, "qwen", true)));
        when(repo.listProps(23L)).thenReturn(props(23, "sk-db-v1"));
        service.scheduledRefresh();
        assertEquals("sk-db-v1", registry.getProvider("qwen").getApiKey());

        // DB 故障：listProviders 抛异常，刷新失败，旧路由保留
        when(repo.listProviders()).thenThrow(new RuntimeException("db down"));
        service.scheduledRefresh();

        assertNotNull(registry.getProvider("qwen"));
        assertEquals("sk-db-v1", registry.getProvider("qwen").getApiKey());
    }

    @Test
    void loadDbProviders_跳过禁用提供商() {
        when(repo.listProviders()).thenReturn(List.of(
                provider(1, "qwen", true),
                provider(2, "deepseek", false))); // 禁用
        when(repo.listProps(1L)).thenReturn(props(1, "sk-qwen"));
        when(repo.listProps(2L)).thenReturn(props(2, "sk-ds"));

        int n = service.loadDbProviders();

        assertEquals(1, n); // 只注册了 qwen
        assertNotNull(registry.getProvider("qwen"));
        assertNull(registry.getProvider("deepseek")); // 禁用的未注册
    }

    @Test
    void loadDbProviders_跳过缺apiKey的提供商() {
        when(repo.listProviders()).thenReturn(List.of(
                provider(1, "qwen", true),
                provider(2, "doubao", true))); // 无 key
        when(repo.listProps(1L)).thenReturn(props(1, "sk-qwen"));
        when(repo.listProps(2L)).thenReturn(List.of()); // 无 api_key prop

        int n = service.loadDbProviders();

        assertEquals(1, n);
        assertNotNull(registry.getProvider("qwen"));
        assertNull(registry.getProvider("doubao"));
    }

    @Test
    void listProviders_apiKey不回传只返回hasApiKey() {
        when(repo.listProviders()).thenReturn(List.of(provider(23, "qwen", true)));
        when(repo.listProps(23L)).thenReturn(props(23, "sk-db-NEW"));
        when(repo.listModels(anyLong())).thenReturn(List.of());
        service.loadDbProviders();

        Map<String, Object> result = service.listProviders();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("providers");
        Map<String, Object> qwen = items.stream()
                .filter(m -> "qwen".equals(m.get("name")))
                .findFirst().orElseThrow();

        // 安全：绝不回传 apiKey，只返回 hasApiKey 布尔
        assertTrue(qwen.containsKey("hasApiKey"));
        assertEquals(true, qwen.get("hasApiKey"));
        assertNull(qwen.get("apiKey"));
    }
}
