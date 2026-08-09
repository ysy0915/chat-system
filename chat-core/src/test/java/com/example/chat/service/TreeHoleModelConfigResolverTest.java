package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.ModelNotAvailableException;
import com.example.chat.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TreeHoleModelConfigResolver 单元测试
 * 覆盖：DB读取成功/失败、兜底默认值、配置缺失抛异常
 */
@ExtendWith(MockitoExtension.class)
class TreeHoleModelConfigResolverTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    private LlmConfigProperties llmConfig;
    private TreeHoleModelConfigResolver resolver;

    @BeforeEach
    void setUp() {
        llmConfig = new LlmConfigProperties();
        llmConfig.setModel("qwen-plus");
        llmConfig.setApiKey("sk-default-key");
        llmConfig.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        resolver = new TreeHoleModelConfigResolver(modelConfigRepository, llmConfig);
    }

    // ────────── resolveMainModel ──────────

    @Test
    @DisplayName("resolveMainModel：DB返回id=2的配置时直接使用")
    void resolveMainModel_fromDb() {
        ModelConfig dbConfig = configWithDefaults(2L, "qwen", "qwen-max", "sk-db-key");
        when(modelConfigRepository.findById(2L)).thenReturn(dbConfig);

        ModelConfig result = resolver.resolveMainModel();

        assertSame(dbConfig, result);
        assertEquals("qwen-max", result.model);
        assertEquals("sk-db-key", result.apiKeyEncrypted);
    }

    @Test
    @DisplayName("resolveMainModel：DB返回null时使用LlmConfigProperties兜底")
    void resolveMainModel_fallbackWhenNull() {
        when(modelConfigRepository.findById(2L)).thenReturn(null);

        ModelConfig result = resolver.resolveMainModel();

        assertNotNull(result);
        assertEquals("qwen", result.provider);
        assertEquals("qwen-plus", result.model);
        assertEquals("sk-default-key", result.apiKeyEncrypted);
    }

    @Test
    @DisplayName("resolveMainModel：DB抛异常时使用LlmConfigProperties兜底")
    void resolveMainModel_fallbackOnException() {
        when(modelConfigRepository.findById(2L)).thenThrow(new RuntimeException("DB down"));

        ModelConfig result = resolver.resolveMainModel();

        assertNotNull(result);
        assertEquals("qwen-plus", result.model);
        assertEquals("sk-default-key", result.apiKeyEncrypted);
    }

    // ────────── resolveZhipuOrThrow ──────────

    @Test
    @DisplayName("resolveZhipuOrThrow：id=9配置存在且有效时返回")
    void resolveZhipu_whenPrimaryExists() {
        ModelConfig zhipu = new ModelConfig();
        zhipu.id = 9L;
        zhipu.provider = "zhipu";
        zhipu.model = "glm-4.6v-flash";
        zhipu.modelType = "text_parse";
        zhipu.enabled = true;
        when(modelConfigRepository.findById(9L)).thenReturn(zhipu);

        ModelConfig result = resolver.resolveZhipuOrThrow();

        assertNotNull(result);
        assertEquals("zhipu", result.provider);
    }

    @Test
    @DisplayName("resolveZhipuOrThrow：主配置无效时从type列表查找兜底")
    void resolveZhipu_fallbackFromTypeList() {
        // id=9 不是 text_parse 类型，走兜底
        ModelConfig invalid = new ModelConfig();
        invalid.id = 9L;
        invalid.provider = "zhipu";
        invalid.modelType = "chat";
        invalid.enabled = true;
        when(modelConfigRepository.findById(9L)).thenReturn(invalid);

        ModelConfig fallback = new ModelConfig();
        fallback.id = 10L;
        fallback.provider = "zhipu";
        fallback.modelType = "text_parse";
        when(modelConfigRepository.findAllEnabledByType("text_parse"))
                .thenReturn(List.of(fallback));

        ModelConfig result = resolver.resolveZhipuOrThrow();

        assertNotNull(result);
        assertEquals(10L, result.id);
    }

    @Test
    @DisplayName("resolveZhipuOrThrow：无任何可用智谱配置时抛ModelNotAvailableException")
    void resolveZhipu_throwsWhenNoConfig() {
        when(modelConfigRepository.findById(9L)).thenReturn(null);
        when(modelConfigRepository.findAllEnabledByType("text_parse")).thenReturn(Collections.emptyList());

        assertThrows(ModelNotAvailableException.class, () -> resolver.resolveZhipuOrThrow());
    }

    // ────────── resolveImageParseOrThrow ──────────

    @Test
    @DisplayName("resolveImageParseOrThrow：id=8配置存在时返回")
    void resolveImageParse_whenExists() {
        ModelConfig imgConfig = new ModelConfig();
        imgConfig.id = 8L;
        imgConfig.provider = "qwen";
        imgConfig.model = "qwen-vl-plus";
        imgConfig.modelType = "image_parse";
        when(modelConfigRepository.findById(8L)).thenReturn(imgConfig);

        ModelConfig result = resolver.resolveImageParseOrThrow();

        assertNotNull(result);
        assertEquals("image_parse", result.modelType);
    }

    @Test
    @DisplayName("resolveImageParseOrThrow：无配置时抛ModelNotAvailableException")
    void resolveImageParse_throwsWhenNoConfig() {
        when(modelConfigRepository.findById(8L)).thenReturn(null);
        when(modelConfigRepository.findAllEnabledByType("image_parse")).thenReturn(Collections.emptyList());

        assertThrows(ModelNotAvailableException.class, () -> resolver.resolveImageParseOrThrow());
    }

    // ────────── helpers ──────────

    private static ModelConfig configWithDefaults(Long id, String provider, String model, String apiKey) {
        ModelConfig c = new ModelConfig();
        c.id = id;
        c.provider = provider;
        c.model = model;
        c.apiKeyEncrypted = apiKey;
        c.enabled = true;
        return c;
    }
}
