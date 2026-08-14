package com.example.chat.util;

import com.example.chat.entity.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BaseUrlResolver 单元测试
 */
@DisplayName("BaseUrlResolver 模型 baseUrl 解析器")
class BaseUrlResolverTest {

    private BaseUrlResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BaseUrlResolver(new ObjectMapper());
    }

    private ModelConfig config(String provider, String metaJson) {
        ModelConfig c = new ModelConfig();
        c.provider = provider;
        c.metaJson = metaJson;
        return c;
    }

    @Test
    @DisplayName("metaJson 中 baseUrl 驼峰优先")
    void resolve_metaJsonCamelCase() {
        ModelConfig c = config("qwen", "{\"baseUrl\":\"https://custom.api.com/v1\"}");
        assertEquals("https://custom.api.com/v1", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("metaJson 中 base_url 下划线也能读取")
    void resolve_metaJsonSnakeCase() {
        ModelConfig c = config("qwen", "{\"base_url\":\"https://snake.api.com/v1\"}");
        assertEquals("https://snake.api.com/v1", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("deepseek provider 返回默认地址")
    void resolve_deepseek_default() {
        ModelConfig c = config("deepseek", null);
        assertEquals("https://api.deepseek.com/v1", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("doubao provider 返回默认地址")
    void resolve_doubao_default() {
        ModelConfig c = config("doubao", null);
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("qwen provider 返回传入的 defaultBaseUrl")
    void resolve_qwen_default() {
        ModelConfig c = config("qwen", null);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                resolver.resolve(c, "https://dashscope.aliyuncs.com/compatible-mode/v1"));
    }

    @Test
    @DisplayName("metaJson 解析失败回退到 provider 默认地址")
    void resolve_invalidMetaJson_fallback() {
        ModelConfig c = config("deepseek", "invalid json");
        assertEquals("https://api.deepseek.com/v1", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("provider 为 null 返回 defaultBaseUrl")
    void resolve_nullProvider_returnsDefault() {
        ModelConfig c = config(null, null);
        assertEquals("https://default.com", resolver.resolve(c, "https://default.com"));
    }

    @Test
    @DisplayName("provider 和 defaultBaseUrl 都为 null 返回 dashscope 兜底")
    void resolve_allNull_returnsDashscope() {
        ModelConfig c = config(null, null);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", resolver.resolve(c, null));
    }

    @Test
    @DisplayName("provider 大写也能匹配")
    void resolve_upperCaseProvider_matched() {
        ModelConfig c = config("DeepSeek", null);
        assertEquals("https://api.deepseek.com/v1", resolver.resolve(c, "https://default.com"));
    }
}
