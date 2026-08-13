package com.example.chat.util;

import com.example.chat.entity.ModelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseUrlResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BaseUrlResolver resolver = new BaseUrlResolver(objectMapper);

    @Test
    @DisplayName("metaJson 为空使用默认值")
    void testNullMetaJson() {
        ModelConfig config = new ModelConfig();
        config.metaJson = null;
        config.provider = "unknown";
        // unknown provider + null default → fallback to qwen-compat
        String result = resolver.resolve(config, null);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", result);
    }

    @Test
    @DisplayName("metaJson 中无 baseUrl 走 provider 匹配")
    void testMetaJsonNoBaseUrl() {
        ModelConfig config = new ModelConfig();
        config.metaJson = "{\"other\":\"value\"}";
        config.provider = "deepseek";
        String result = resolver.resolve(config, "https://default.com/v1");
        assertEquals("https://api.deepseek.com/v1", result);
    }

    @Test
    @DisplayName("metaJson+baseUrl 返回解析结果")
    void testMetaJsonWithBaseUrl() {
        ModelConfig config = new ModelConfig();
        config.metaJson = "{\"baseUrl\":\"https://custom.com/v1\"}";
        String result = resolver.resolve(config, "https://default.com/v1");
        assertEquals("https://custom.com/v1", result);
    }

    @Test
    @DisplayName("metaJson+base_url 下划线")
    void testMetaJsonWithBaseUrlUnderscore() {
        ModelConfig config = new ModelConfig();
        config.metaJson = "{\"base_url\":\"https://alt.com/v1\"}";
        String result = resolver.resolve(config, null);
        assertEquals("https://alt.com/v1", result);
    }

    @Test
    @DisplayName("doubao provider")
    void testDoubaoProvider() {
        ModelConfig config = new ModelConfig();
        config.metaJson = null;
        config.provider = "doubao";
        String result = resolver.resolve(config, null);
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", result);
    }

    @Test
    @DisplayName("qwen provider")
    void testQwenProvider() {
        ModelConfig config = new ModelConfig();
        config.metaJson = null;
        config.provider = "qwen";
        String result = resolver.resolve(config, null);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", result);
    }

    @Test
    @DisplayName("bad json falls back")
    void testBadJsonFallsBack() {
        ModelConfig config = new ModelConfig();
        config.metaJson = "{bad json";
        config.provider = "qwen";
        String result = resolver.resolve(config, null);
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", result);
    }
}
