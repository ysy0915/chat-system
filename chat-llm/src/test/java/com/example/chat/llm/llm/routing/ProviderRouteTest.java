package com.example.chat.llm.llm.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProviderRoute / ModelRoute 路由模型测试 — 模型匹配、默认模型选择、调用类型。
 */
class ProviderRouteTest {

    // ============ 模型匹配 ============

    @Test
    void shouldMatchExactModel() {
        ProviderRoute provider = providerWith(models(
                model("deepseek-chat", true, 0),
                model("deepseek-reasoner", false, 1)));

        ModelRoute m = provider.matchModel("deepseek-reasoner");

        assertEquals("deepseek-reasoner", m.getName());
    }

    @Test
    void shouldFallbackToDefaultModelWhenModelUnknown() {
        ProviderRoute provider = providerWith(models(
                model("deepseek-chat", true, 0),
                model("deepseek-reasoner", false, 1)));

        ModelRoute m = provider.matchModel("unknown-model");

        assertEquals("deepseek-chat", m.getName());
    }

    @Test
    void shouldUseDefaultFlagWhenModelBlank() {
        ProviderRoute provider = providerWith(models(
                model("deepseek-chat", true, 0),
                model("deepseek-reasoner", false, 1)));

        ModelRoute m = provider.matchModel(null);

        assertEquals("deepseek-chat", m.getName());
    }

    @Test
    void shouldSkipDisabledModels() {
        ProviderRoute provider = providerWith(models(
                model("disabled-model", true, 0),
                model("enabled-model", false, 1)));
        provider.getModels().get(0).setEnabled(false);

        ModelRoute m = provider.matchModel("disabled-model");

        // 禁用的默认模型被跳过，回退到最低优先级的启用模型
        assertEquals("enabled-model", m.getName());
    }

    @Test
    void shouldPickLowestPriorityWhenNoDefaultModel() {
        ProviderRoute provider = providerWith(models(
                model("m1", false, 5),
                model("m2", false, 2),
                model("m3", false, 8)));

        ModelRoute m = provider.getDefaultModel();

        assertEquals("m2", m.getName());
    }

    @Test
    void shouldReturnNullWhenNoEnabledModels() {
        ProviderRoute provider = providerWith(models(model("m1", false, 0)));
        provider.getModels().get(0).setEnabled(false);

        assertNull(provider.matchModel("m1"));
        assertNull(provider.getDefaultModel());
    }

    // ============ 模型类型过滤 ============

    @Test
    void shouldFilterModelsByType() {
        ProviderRoute provider = providerWith(models(
                model("chat-1", true, 0),
                model("embed-1", true, 1),
                model("rerank-1", true, 2)));
        provider.getModels().get(0).setModelType("chat");
        provider.getModels().get(1).setModelType("embedding");
        provider.getModels().get(2).setModelType("rerank");

        List<ModelRoute> chat = provider.listModelsByType("chat");
        List<ModelRoute> embed = provider.listModelsByType("embedding");

        assertEquals(1, chat.size());
        assertEquals("chat-1", chat.get(0).getName());
        assertEquals(1, embed.size());
        assertEquals("embed-1", embed.get(0).getName());
    }

    @Test
    void shouldListAllWhenTypeBlank() {
        ProviderRoute provider = providerWith(models(
                model("m1", true, 0),
                model("m2", true, 1)));

        assertEquals(2, provider.listModelsByType(null).size());
    }

    // ============ 调用类型 ============

    @Test
    void shouldDetectSdkAndRestTypes() {
        ProviderRoute sdk = new ProviderRoute();
        sdk.setInvokeType("sdk");
        assertTrue(sdk.isSdk());
        assertFalse(sdk.isRest());

        ProviderRoute rest = new ProviderRoute();
        rest.setInvokeType("rest");
        assertFalse(rest.isSdk());
        assertTrue(rest.isRest());

        ProviderRoute unspecified = new ProviderRoute();
        assertTrue(unspecified.isRest());
        assertFalse(unspecified.isSdk());
    }

    @Test
    void modelRouteShouldMatchCaseInsensitively() {
        ModelRoute m = model("DeepSeek-Chat", true, 0);

        assertTrue(m.matches("deepseek-chat"));
        assertTrue(m.matches("DEEPSEEK-CHAT"));
        assertFalse(m.matches("other-model"));
    }

    // ============ helpers ============

    private ProviderRoute providerWith(List<ModelRoute> models) {
        ProviderRoute p = new ProviderRoute();
        p.setName("deepseek");
        p.setApiKey("sk-test");
        p.setEnabled(true);
        p.setModels(models);
        return p;
    }

    private List<ModelRoute> models(ModelRoute... models) {
        return List.of(models);
    }

    private ModelRoute model(String name, boolean isDefault, int priority) {
        ModelRoute m = new ModelRoute();
        m.setName(name);
        m.setModelType("chat");
        m.setEnabled(true);
        m.setDefault(isDefault);
        m.setPriority(priority);
        return m;
    }
}
