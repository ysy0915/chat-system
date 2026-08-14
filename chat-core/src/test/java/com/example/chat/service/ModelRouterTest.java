package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ModelRouter 单元测试
 */
@DisplayName("ModelRouter 模型路由器")
class ModelRouterTest {

    private ModelConfigRepository repository;
    private RedisTemplate<String, String> redisTemplate;
    private ModelRouter router;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(ModelConfigRepository.class);
        redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        router = new ModelRouter(repository, redisTemplate);
    }

    private ModelConfig config(String provider, String model, int priority) {
        ModelConfig c = new ModelConfig();
        c.provider = provider;
        c.model = model;
        c.priority = priority;
        c.enabled = true;
        c.modelType = "chat";
        return c;
    }

    @Test
    @DisplayName("loadChatModels 有可用模型按优先级排序")
    void loadChatModels_sorted() {
        ModelConfig c1 = config("doubao", "doubao-pro", 10);
        ModelConfig c2 = config("qwen", "qwen-plus", 5);
        when(repository.findAllEnabled()).thenReturn(List.of(c1, c2));
        List<ModelConfig> result = router.loadChatModels("doubao", "default", "key");
        assertEquals(2, result.size());
        assertEquals("qwen", result.get(0).provider);
        assertEquals("doubao", result.get(1).provider);
    }

    @Test
    @DisplayName("loadChatModels 无可用模型返回降级配置")
    void loadChatModels_empty_returnsFallback() {
        when(repository.findAllEnabled()).thenReturn(List.of());
        List<ModelConfig> result = router.loadChatModels("doubao", "doubao-pro", "key123");
        assertEquals(1, result.size());
        assertEquals("doubao", result.get(0).provider);
        assertEquals("doubao-pro", result.get(0).model);
        assertEquals("key123", result.get(0).apiKeyEncrypted);
    }

    @Test
    @DisplayName("loadChatModels 过滤非 chat 类型")
    void loadChatModels_filtersNonChatType() {
        ModelConfig chatConfig = config("doubao", "doubao-pro", 10);
        ModelConfig imageConfig = config("doubao", "doubao-image", 5);
        imageConfig.modelType = "image";
        when(repository.findAllEnabled()).thenReturn(List.of(chatConfig, imageConfig));
        List<ModelConfig> result = router.loadChatModels("doubao", "default", "key");
        assertEquals(1, result.size());
        assertEquals("doubao-pro", result.get(0).model);
    }

    @Test
    @DisplayName("selectBestProvider 代码问题返回 deepseek")
    void selectBestProvider_code_returnsDeepSeek() {
        assertEquals("deepseek", router.selectBestProvider("帮我写一段代码"));
        assertEquals("deepseek", router.selectBestProvider("这个bug怎么修复"));
        assertEquals("deepseek", router.selectBestProvider("java算法实现"));
    }

    @Test
    @DisplayName("selectBestProvider 创意问题返回 qwen")
    void selectBestProvider_creative_returnsQwen() {
        assertEquals("qwen", router.selectBestProvider("帮我写诗一首关于秋天"));
        assertEquals("qwen", router.selectBestProvider("给我创作一个故事"));
    }

    @Test
    @DisplayName("selectBestProvider 普通问题返回 doubao")
    void selectBestProvider_normal_returnsDoubao() {
        assertEquals("doubao", router.selectBestProvider("你好"));
        assertEquals("doubao", router.selectBestProvider("今天吃什么"));
    }

    @Test
    @DisplayName("selectBestProvider 空输入返回 doubao")
    void selectBestProvider_empty_returnsDoubao() {
        assertEquals("doubao", router.selectBestProvider(""));
        assertEquals("doubao", router.selectBestProvider(null));
    }
}
