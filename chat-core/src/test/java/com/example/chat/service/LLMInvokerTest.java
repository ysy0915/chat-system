package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.factory.LLMStrategyFactory;
import com.example.chat.strategy.LLMStrategy;
import com.example.chat.util.BaseUrlResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LLMInvoker 测试 — Mock 全部依赖
 */
@ExtendWith(MockitoExtension.class)
class LLMInvokerTest {

    @Mock private LLMStrategyFactory strategyFactory;
    @Mock private BaseUrlResolver baseUrlResolver;
    @Mock private LLMCallRecorder callRecorder;
    @Mock private LLMStrategy strategy;

    private static final String DEFAULT_URL = "https://default.api.com";
    private static final String DEFAULT_KEY = "sk-default";

    private LLMInvoker invoker;

    @BeforeEach
    void setUp() {
        lenient().when(strategyFactory.getStrategy(anyString())).thenReturn(strategy);
        invoker = new LLMInvoker(strategyFactory, baseUrlResolver, callRecorder);
    }

    // ---- invoke() 成功 ----

    @Test
    void shouldInvokeSuccessfully() throws Exception {
        ModelConfig config = buildConfig("deepseek", "deepseek-chat", "https://api.ds.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://api.ds.com");
        when(strategy.invoke(eq("https://api.ds.com"), eq("sk-xxx"), eq("deepseek-chat"),
                anyList(), eq(0.7))).thenReturn("Response text");

        String result = invoker.invoke(config,
                List.of(LLMMessage.user("Hi")), 0.7, "chat", DEFAULT_URL, DEFAULT_KEY);

        assertEquals("Response text", result);
        verify(callRecorder).record(eq("deepseek"), eq("deepseek-chat"), eq("chat"), eq(true),
                anyLong(), eq(13));
    }

    @Test
    void shouldUseConfigApiKeyOverDefault() throws Exception {
        ModelConfig config = buildConfig("qwen", "qwen-plus", "https://dashscope.aliyuncs.com", "sk-custom");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://dashscope.aliyuncs.com");
        when(strategy.invoke(anyString(), eq("sk-custom"), anyString(), anyList(), anyDouble()))
                .thenReturn("OK");

        invoker.invoke(config, List.of(LLMMessage.user("test")), 0.3, "chat", DEFAULT_URL, DEFAULT_KEY);

        verify(strategy).invoke(anyString(), eq("sk-custom"), anyString(), anyList(), anyDouble());
    }

    // ---- invoke() 失败 ----

    @Test
    void shouldThrowOnInvokeFailure() throws Exception {
        ModelConfig config = buildConfig("deepseek", "deepseek-chat", "https://api.ds.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://api.ds.com");
        when(strategy.invoke(anyString(), anyString(), anyString(), anyList(), anyDouble()))
                .thenThrow(new RuntimeException("timeout"));

        try {
            invoker.invoke(config, List.of(LLMMessage.user("Hi")), 0.7, "chat", DEFAULT_URL, DEFAULT_KEY);
            fail("should throw");
        } catch (RuntimeException e) {
            assertEquals("timeout", e.getMessage());
        }

        verify(callRecorder).record(eq("deepseek"), eq("deepseek-chat"), eq("chat"), eq(false),
                anyLong(), eq(0));
    }

    // ---- invokeStream() ----

    @Test
    void shouldInvokeStreamSuccessfully() throws Exception {
        ModelConfig config = buildConfig("deepseek", "deepseek-chat", "https://api.ds.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://api.ds.com");
        when(strategy.invokeStream(anyString(), anyString(), anyString(), anyList(), anyDouble(), any()))
                .thenReturn("streamed response");

        StringBuilder sb = new StringBuilder();
        String result = invoker.invokeStream(config, List.of(LLMMessage.user("Hi")),
                0.7, "treehole", DEFAULT_URL, DEFAULT_KEY, sb::append);

        assertEquals("streamed response", result);
        verify(callRecorder).record(eq("deepseek"), eq("deepseek-chat"), eq("treehole"), eq(true),
                anyLong(), eq(17));
    }

    @Test
    void shouldThrowOnStreamFailure() throws Exception {
        ModelConfig config = buildConfig("deepseek", "deepseek-chat", "https://api.ds.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://api.ds.com");
        when(strategy.invokeStream(anyString(), anyString(), anyString(), anyList(), anyDouble(), any()))
                .thenThrow(new RuntimeException("stream error"));

        try {
            invoker.invokeStream(config, List.of(LLMMessage.user("Hi")),
                    0.7, "chat", DEFAULT_URL, DEFAULT_KEY, chunk -> {});
            fail("should throw");
        } catch (RuntimeException e) {
            assertEquals("stream error", e.getMessage());
        }

        verify(callRecorder).record(eq("deepseek"), eq("deepseek-chat"), eq("chat"), eq(false),
                anyLong(), eq(0));
    }

    // ---- invokeWithRouting 降级 ----

    @Test
    void shouldThrowWhenRoutingNotEnabled() {
        LLMInvoker noRouter = new LLMInvoker(strategyFactory, baseUrlResolver, callRecorder);

        assertThrows(IllegalStateException.class, () ->
            noRouter.invokeWithRouting("hello", "chat", null,
                    List.of(LLMMessage.user("hello")), 0.5, DEFAULT_URL, DEFAULT_KEY));
    }

    // ---- 场景透传 ----

    @Test
    void shouldRecordCorrectSceneOnSuccess() throws Exception {
        ModelConfig config = buildConfig("deepseek", "deepseek-chat", "https://api.ds.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://api.ds.com");
        when(strategy.invoke(anyString(), anyString(), anyString(), anyList(), anyDouble()))
                .thenReturn("ok");

        invoker.invoke(config, List.of(LLMMessage.user("x")), 0.5, "debate", DEFAULT_URL, DEFAULT_KEY);

        verify(callRecorder).record(eq("deepseek"), eq("deepseek-chat"), eq("debate"), eq(true),
                anyLong(), eq(2));
    }

    @Test
    void shouldRecordCorrectSceneOnStream() throws Exception {
        ModelConfig config = buildConfig("qwen", "qwen-plus", "https://dashscope.aliyuncs.com", "sk-xxx");
        when(baseUrlResolver.resolve(config, DEFAULT_URL)).thenReturn("https://dashscope.aliyuncs.com");
        when(strategy.invokeStream(anyString(), anyString(), anyString(), anyList(), anyDouble(), any()))
                .thenReturn("ok");

        invoker.invokeStream(config, List.of(LLMMessage.user("x")),
                0.5, "personal", DEFAULT_URL, DEFAULT_KEY, chunk -> {});

        verify(callRecorder).record(eq("qwen"), eq("qwen-plus"), eq("personal"), eq(true),
                anyLong(), eq(2));
    }

    private ModelConfig buildConfig(String provider, String model, String baseUrl, String apiKey) {
        ModelConfig config = new ModelConfig();
        config.provider = provider;
        config.model = model;
        config.apiKeyEncrypted = apiKey;
        config.metaJson = "{\"baseUrl\":\"" + baseUrl + "\"}";
        return config;
    }
}
