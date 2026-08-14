package com.example.chat.llm.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.DirectLLMClient;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TripleExtractionService 单元测试
 */
@DisplayName("TripleExtractionService 三元组抽取服务")
class TripleExtractionServiceTest {

    private TripleExtractionService service;
    private ModelConfigRepository repository;
    private DirectLLMClient directLLMClient;
    private LlmConfigProperties llmConfig;
    private BaseUrlResolver baseUrlResolver;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        repository = mock(ModelConfigRepository.class);
        baseUrlResolver = mock(BaseUrlResolver.class);
        llmConfig = mock(LlmConfigProperties.class);
        directLLMClient = mock(DirectLLMClient.class);
        service = new TripleExtractionService(objectMapper, repository, baseUrlResolver, llmConfig, directLLMClient);

        // 默认 stub
        when(llmConfig.getApiKey()).thenReturn("sk-test");
        when(llmConfig.getBaseUrl()).thenReturn("https://default.api/v1");
        when(llmConfig.getModel()).thenReturn("qwen-plus");
    }

    private ModelConfig config(String provider, String model) {
        ModelConfig c = new ModelConfig();
        c.provider = provider;
        c.model = model;
        c.apiKeyEncrypted = "sk-chosen";
        c.enabled = true;
        return c;
    }

    @Test
    @DisplayName("LLM 返回有效 JSON 三元组 → 正确解析")
    void extractTriples_validJson_parsed() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of(config("qwen", "qwen-plus")));
        when(baseUrlResolver.resolve(any(), anyString())).thenReturn("https://custom.api/v1");
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("{\"triples\":[{\"subject\":\"机器学习\",\"relation\":\"属于\",\"object\":\"人工智能\"}]}");

        List<Map<String, String>> result = service.extractTriples("什么是机器学习", "机器学习是人工智能的分支");
        assertEquals(1, result.size());
        assertEquals("机器学习", result.get(0).get("subject"));
        assertEquals("属于", result.get(0).get("relation"));
        assertEquals("人工智能", result.get(0).get("object"));
    }

    @Test
    @DisplayName("LLM 返回空内容 → 返回空列表")
    void extractTriples_emptyResponse_returnsEmpty() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of());
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("");

        assertTrue(service.extractTriples("问题", "回答").isEmpty());
    }

    @Test
    @DisplayName("LLM 返回 null → 返回空列表")
    void extractTriples_nullResponse_returnsEmpty() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of());
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn(null);

        assertTrue(service.extractTriples("问题", "回答").isEmpty());
    }

    @Test
    @DisplayName("LLM 返回无效 JSON → 返回空列表")
    void extractTriples_invalidJson_returnsEmpty() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of());
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("这不是JSON");

        assertTrue(service.extractTriples("问题", "回答").isEmpty());
    }

    @Test
    @DisplayName("LLM 返回空三元组数组 → 返回空列表")
    void extractTriples_emptyTriplesArray_returnsEmpty() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of());
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("{\"triples\":[]}");

        assertTrue(service.extractTriples("问题", "回答").isEmpty());
    }

    @Test
    @DisplayName("超长问答被截断后仍能正常调用")
    void extractTriples_longText_truncated() {
        when(repository.findAllEnabledByType("chat")).thenReturn(List.of());
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("{\"triples\":[]}");

        String longQuestion = "问题".repeat(500);
        String longAnswer = "回答".repeat(2000);
        assertDoesNotThrow(() -> service.extractTriples(longQuestion, longAnswer));
    }

    @Test
    @DisplayName("Repository 异常时使用默认配置降级调用")
    void extractTriples_repoException_fallbackToDefault() {
        when(repository.findAllEnabledByType("chat")).thenThrow(new org.springframework.dao.DataAccessException("DB down") {});
        when(directLLMClient.call(anyString(), anyString(), anyString(), anyList(), anyDouble(), anyInt()))
                .thenReturn("{\"triples\":[]}");

        assertTrue(service.extractTriples("问题", "回答").isEmpty());
    }
}
