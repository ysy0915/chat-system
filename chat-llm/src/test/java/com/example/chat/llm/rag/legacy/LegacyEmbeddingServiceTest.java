package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LegacyEmbeddingService 单元测试
 *
 * 不测试真实 HTTP 调用，仅测试参数校验和 getter
 */
@DisplayName("LegacyEmbeddingService 旧版 Embedding 服务")
class LegacyEmbeddingServiceTest {

    @Test
    @DisplayName("embedBatch 空列表返回空列表")
    void embedBatch_empty_returnsEmpty() throws Exception {
        LegacyEmbeddingService service = createService();
        assertTrue(service.embedBatch(java.util.List.of()).isEmpty());
    }

    @Test
    @DisplayName("embedBatch null 返回空列表")
    void embedBatch_null_returnsEmpty() throws Exception {
        LegacyEmbeddingService service = createService();
        assertTrue(service.embedBatch(null).isEmpty());
    }

    @Test
    @DisplayName("getDimension 返回配置的维度")
    void getDimension_returnsConfigured() throws Exception {
        LegacyEmbeddingService service = createService();
        assertEquals(1024, service.getDimension());
    }

    @Test
    @DisplayName("getModel 返回配置的模型名")
    void getModel_returnsConfigured() throws Exception {
        LegacyEmbeddingService service = createService();
        assertEquals("text-embedding-v3", service.getModel());
    }

    @Test
    @DisplayName("getProvider 返回配置的 provider")
    void getProvider_returnsConfigured() throws Exception {
        LegacyEmbeddingService service = createService();
        assertEquals("dashscope", service.getProvider());
    }

    /**
     * 创建实例并用反射设置 @Value 字段（绕过 Spring 注入）
     */
    private LegacyEmbeddingService createService() throws Exception {
        LegacyEmbeddingService service = new LegacyEmbeddingService(new com.fasterxml.jackson.databind.ObjectMapper());
        setField(service, "provider", "dashscope");
        setField(service, "apiMode", "dashscope");
        setField(service, "baseUrl", "https://dashscope.aliyuncs.com/api/v1");
        setField(service, "apiKey", "sk-test");
        setField(service, "model", "text-embedding-v3");
        setField(service, "dimension", 1024);
        return service;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
