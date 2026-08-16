package com.example.chat.llm.service;

import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.config.LLMConfig;
import com.example.chat.llm.routing.LLMProviderRegistry;
import com.example.chat.llm.routing.ModelRoute;
import com.example.chat.llm.routing.ProviderRoute;
import com.example.chat.llm.metrics.LlmMetrics;
import com.example.chat.llm.strategy.LLMProviderStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLMInvokeService 测试 — 熔断 / 重试 / 限流 / 故障转移 四层弹性策略。
 *
 * <p>使用真实 Resilience4j Registry + Mock Provider 策略，验证弹性链路行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class LLMInvokeServiceTest {

    @Mock private LLMProviderStrategy mainStrategy;
    @Mock private LLMProviderStrategy altStrategy;

    private LLMConfig llmConfig;
    private LLMProviderRegistry registry;
    private CircuitBreakerRegistry cbRegistry;
    private RetryRegistry retryRegistry;
    private RateLimiterRegistry rateLimiterRegistry;
    private LLMInvokeService service;

    @BeforeEach
    void setUp() {
        llmConfig = new LLMConfig();
        registry = new LLMProviderRegistry(llmConfig, new ObjectMapper());
        registerProvider("deepseek", "deepseek-chat", mainStrategy);
        registerProvider("qwen", "qwen-plus", altStrategy);
        // 默认弹性配置：不重试、高限流、默认熔断
        cbRegistry = CircuitBreakerRegistry.ofDefaults();
        retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        rateLimiterRegistry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(1000)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(1))
                .build());
        service = new LLMInvokeService(registry, cbRegistry,
                retryRegistry, rateLimiterRegistry, new LlmMetrics(new SimpleMeterRegistry()));
    }

    // ============ 基本调用 ============

    @Test
    void shouldInvokeSuccessfully() {
        when(mainStrategy.invoke(any()))
                .thenReturn(LangChainResponse.ok("你好", "deepseek", "deepseek-chat"));

        LangChainResponse resp = service.invoke(request("deepseek", "deepseek-chat"));

        assertTrue(resp.isSuccess());
        assertEquals("你好", resp.getContent());
        assertEquals("CHAT", resp.getBizType());
        assertEquals("deepseek", resp.getProvider());
    }

    @Test
    void shouldFailWhenProviderUnknown() {
        LangChainRequest req = request("unknown-provider", "some-model");

        LangChainResponse resp = service.invoke(req);

        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("未知提供商"));
        verify(mainStrategy, never()).invoke(any());
    }

    @Test
    void shouldRecordTraceIdAndElapsed() {
        when(mainStrategy.invoke(any()))
                .thenReturn(LangChainResponse.ok("ok", "deepseek", "deepseek-chat"));

        LangChainResponse resp = service.invoke(request("deepseek", "deepseek-chat"));

        assertEquals("N/A", resp.getTraceId());
        assertTrue(resp.getElapsedMs() >= 0);
    }

    // ============ 重试 ============

    @Test
    void shouldRetryAndEventuallySucceed() {
        retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .retryOnException(e -> true)
                .build());
        service = new LLMInvokeService(registry, cbRegistry,
                retryRegistry, rateLimiterRegistry, new LlmMetrics(new SimpleMeterRegistry()));
        when(mainStrategy.invoke(any()))
                .thenThrow(new RuntimeException("first"))
                .thenReturn(LangChainResponse.ok("ok", "deepseek", "deepseek-chat"));

        LangChainResponse resp = service.invoke(request("deepseek", "deepseek-chat"));

        assertTrue(resp.isSuccess());
        assertEquals("ok", resp.getContent());
        verify(mainStrategy, times(2)).invoke(any());
    }

    @Test
    void shouldGiveUpAfterMaxRetriesAndFallback() {
        retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ZERO)
                .retryOnException(e -> true)
                .failAfterMaxAttempts(true)
                .build());
        service = new LLMInvokeService(registry, cbRegistry,
                retryRegistry, rateLimiterRegistry, new LlmMetrics(new SimpleMeterRegistry()));
        when(mainStrategy.invoke(any())).thenThrow(new RuntimeException("boom"));
        when(altStrategy.invoke(any()))
                .thenReturn(LangChainResponse.ok("备用回复", "qwen", "qwen-plus"));

        LangChainResponse resp = service.invoke(request("deepseek", "deepseek-chat"));

        assertTrue(resp.isSuccess());
        assertTrue(resp.isFallback());
        assertEquals("备用回复", resp.getContent());
        assertEquals("qwen", resp.getProvider());
        verify(mainStrategy, times(2)).invoke(any());
    }

    // ============ 故障转移 ============

    @Test
    void shouldFallbackToAlternateProviderOnFailure() {
        when(mainStrategy.invoke(any())).thenThrow(new RuntimeException("boom"));
        when(altStrategy.invoke(any()))
                .thenReturn(LangChainResponse.ok("备用回复", "qwen", "qwen-plus"));

        LangChainResponse resp = service.invoke(request("deepseek", "deepseek-chat"));

        assertTrue(resp.isSuccess());
        assertTrue(resp.isFallback());
        assertEquals("备用回复", resp.getContent());
        assertEquals("qwen", resp.getProvider());
    }

    @Test
    void shouldNotFallbackWhenRequestHasExtra() {
        when(mainStrategy.invoke(any())).thenThrow(new RuntimeException("boom"));
        LangChainRequest req = request("deepseek", "deepseek-chat");
        req.setExtra(Map.of("baseUrl", "https://custom.com"));

        LangChainResponse resp = service.invoke(req);

        assertFalse(resp.isSuccess());
        assertTrue(resp.isFallback());
        verify(altStrategy, never()).invoke(any());
    }

    // ============ 熔断 ============

    @Test
    void shouldOpenCircuitBreakerAndRejectCalls() {
        // 单 Provider 注册表，避免故障转移干扰熔断断言
        registry = new LLMProviderRegistry(llmConfig, new ObjectMapper());
        registerProvider("deepseek", "deepseek-chat", mainStrategy);
        cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
        service = new LLMInvokeService(registry, cbRegistry,
                retryRegistry, rateLimiterRegistry, new LlmMetrics(new SimpleMeterRegistry()));
        when(mainStrategy.invoke(any())).thenThrow(new RuntimeException("boom"));

        LangChainResponse r1 = service.invoke(request("deepseek", "deepseek-chat"));
        LangChainResponse r2 = service.invoke(request("deepseek", "deepseek-chat"));
        LangChainResponse r3 = service.invoke(request("deepseek", "deepseek-chat"));

        assertFalse(r1.isSuccess());
        assertFalse(r2.isSuccess());
        // 第 3 次被熔断拒绝：fallback=true 且不再调用底层策略
        assertTrue(r3.isFallback());
        verify(mainStrategy, times(2)).invoke(any());
    }

    // ============ 限流 ============

    @Test
    void shouldRejectWhenRateLimited() {
        registry = new LLMProviderRegistry(llmConfig, new ObjectMapper());
        registerProvider("deepseek", "deepseek-chat", mainStrategy);
        rateLimiterRegistry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build());
        service = new LLMInvokeService(registry, cbRegistry,
                retryRegistry, rateLimiterRegistry, new LlmMetrics(new SimpleMeterRegistry()));
        when(mainStrategy.invoke(any()))
                .thenReturn(LangChainResponse.ok("ok", "deepseek", "deepseek-chat"));

        LangChainResponse r1 = service.invoke(request("deepseek", "deepseek-chat"));
        LangChainResponse r2 = service.invoke(request("deepseek", "deepseek-chat"));

        assertTrue(r1.isSuccess());
        assertFalse(r2.isSuccess());
        assertTrue(r2.isFallback());
        assertTrue(r2.getError().contains("RateLimited"));
        verify(mainStrategy, times(1)).invoke(any());
    }

    // ============ 流式调用 ============

    @Test
    void shouldInvokeStreamAndCollectChunks() {
        doAnswer(inv -> {
            Consumer<String> chunk = inv.getArgument(1);
            Runnable complete = inv.getArgument(2);
            chunk.accept("你");
            chunk.accept("好");
            complete.run();
            return null;
        }).when(mainStrategy).invokeStream(any(), any(), any(), any());
        StringBuilder sb = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.invokeStream(request("deepseek", "deepseek-chat"),
                sb::append, () -> completed.set(true), error::set);

        assertEquals("你好", sb.toString());
        assertTrue(completed.get());
        assertNull(error.get());
    }

    @Test
    void shouldReportErrorWhenStreamRouteNotFound() {
        AtomicReference<Throwable> error = new AtomicReference<>();

        service.invokeStream(request("nope", "model"),
                s -> { }, () -> { }, error::set);

        assertTrue(error.get() instanceof RuntimeException);
    }

    // ============ 提供商列表 ============

    @Test
    void shouldListProvidersSorted() {
        List<Map<String, Object>> list = service.listProviders();

        assertEquals(2, list.size());
        assertEquals("deepseek", list.get(0).get("name"));
        assertEquals("rest", list.get(0).get("type"));
        assertEquals("qwen", list.get(1).get("name"));
        assertEquals(1, list.get(0).get("modelCount"));
    }

    // ============ helpers ============

    private void registerProvider(String name, String model, LLMProviderStrategy strategy) {
        ProviderRoute p = new ProviderRoute();
        p.setName(name);
        p.setBaseUrl("https://" + name + ".example.com");
        p.setApiKey("sk-" + name);
        p.setInvokeType("rest");
        p.setEnabled(true);
        ModelRoute m = new ModelRoute();
        m.setName(model);
        m.setModelType("chat");
        m.setEnabled(true);
        m.setDefault(true);
        p.addModel(m);
        registry.register(p, strategy);
    }

    private LangChainRequest request(String provider, String model) {
        LangChainRequest req = new LangChainRequest();
        req.setProvider(provider);
        req.setModel(model);
        req.setMessages(List.of(Map.of("role", "user", "content", "你好")));
        return req;
    }
}
