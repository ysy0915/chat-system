package com.example.chat.llm.service;

import com.example.chat.dto.BizType;
import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.filter.TraceIdFilter;
import com.example.chat.llm.routing.LLMProviderRegistry;
import com.example.chat.llm.routing.LLMProviderRegistry.RouteResult;
import com.example.chat.llm.metrics.LlmMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * <h2>LLM 调用服务 — 带熔断/重试/限流/超时</h2>
 *
 * <p>核心职责：将 LangChainRequest 路由到对应 LLM Provider 并执行，
 * 全程受 Resilience4j 四层弹性策略保护。</p>
 *
 * <h3>弹性策略 (由外到内)</h3>
 * <pre>
 *   RateLimiter  → 限流 (每秒 N 次)
 *   CircuitBreaker → 熔断 (失败率 > 50%，open 30s)
 *   TimeLimiter  → 超时 (120s)
 *   Retry        → 重试 (最多 3 次)
 *   invokeRaw    → 实际调用
 * </pre>
 */
@Service
public class LLMInvokeService {

    private static final Logger log = LoggerFactory.getLogger(LLMInvokeService.class);

    private final LLMProviderRegistry registry;
    private final CircuitBreakerRegistry cbRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final LlmMetrics metrics;

    public LLMInvokeService(LLMProviderRegistry registry,
                            CircuitBreakerRegistry cbRegistry,
                            RetryRegistry retryRegistry,
                            RateLimiterRegistry rateLimiterRegistry,
                            LlmMetrics metrics) {
        this.registry = registry;
        this.cbRegistry = cbRegistry;
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.metrics = metrics;
    }

    /**
     * <h3>带熔断/重试/限流的同步调用</h3>
     *
     * <p>调用链: BizRateLimiter → RateLimiter → CircuitBreaker → Retry → invokeRaw</p>
     */
    @Timed(value = "llm.invoke.timed", percentiles = {0.5, 0.95, 0.99})
    public LangChainResponse invoke(LangChainRequest request) {
        long start = System.currentTimeMillis();
        BizType bizType = BizType.from(request.getBizType());
        request.setBizType(bizType.name());
        RouteResult route = resolveRoute(request);
        if (!route.found()) {
            LangChainResponse r = LangChainResponse.fail(route.error(), request.getProvider());
            r.setTraceId(traceId(request));
            r.setBizType(bizType.name());
            return r;
        }

        // 注入 traceId
        if (request.getTraceId() == null) {
            request.setTraceId(TraceIdFilter.currentTraceId());
        }

        String cbName = "llm-cb-" + route.providerName();
        String retryName = "llm-retry-" + route.providerName();
        String rateName = "llm-rate-" + route.providerName();
        String bizRateName = "llm-biz-rate-" + bizType.name().toLowerCase(Locale.ROOT) + "-" + route.providerName();

        CircuitBreaker cb = cbRegistry.circuitBreaker(cbName);
        Retry retry = retryRegistry.retry(retryName);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateName);
        RateLimiter bizRateLimiter = rateLimiterRegistry.rateLimiter(bizRateName);

        try {
            // 装饰调用: BizRateLimiter → RateLimiter → CircuitBreaker → Retry → invokeRaw
            retry.getEventPublisher().onRetry(e -> {
                Throwable last = e.getLastThrowable();
                log.error("[Retry] {} 第{}次重试, 原因异常 type={} msg={}",
                        retryName, e.getNumberOfRetryAttempts(),
                        last == null ? "null" : last.getClass().getName(),
                        last == null ? "" : String.valueOf(last.getMessage()));
            });
            Supplier<LangChainResponse> decorated = io.github.resilience4j.decorators.Decorators
                    .ofSupplier(() -> invokeRaw(request, route))
                    .withRetry(retry)
                    .withCircuitBreaker(cb)
                    .withRateLimiter(rateLimiter)
                    .withRateLimiter(bizRateLimiter)
                    .decorate();

            LangChainResponse result = decorated.get();

            long elapsed = System.currentTimeMillis() - start;
            boolean success = result.isSuccess();
            result.setTraceId(traceId(request));
            result.setElapsedMs(result.getElapsedMs() > 0 ? result.getElapsedMs() : elapsed);
            result.setBizType(bizType.name());

            // 指标 (含 biz 维度)
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), success, elapsed);
            metrics.recordInvokeDuration(bizType.name(), route.providerName(), route.modelName(), success, elapsed);
            if (success && result.getTotalTokens() != null) {
                int prompt = result.getPromptTokens() != null ? result.getPromptTokens() : 0;
                int completion = result.getCompletionTokens() != null ? result.getCompletionTokens() : 0;
                metrics.recordTokens(bizType.name(), route.providerName(), route.modelName(), prompt, completion);
            }
            // 上下文缓存指标
            if (success && (result.getCacheHitTokens() != null || result.getCacheMissTokens() != null)) {
                int hit = result.getCacheHitTokens() != null ? result.getCacheHitTokens() : 0;
                int miss = result.getCacheMissTokens() != null ? result.getCacheMissTokens() : 0;
                metrics.recordCacheTokens(bizType.name(), route.providerName(), route.modelName(), hit, miss);
            }

            log.debug("[LLMInvoke] biz={} {} / {} → {} ({}ms) cb={} retryOK",
                    bizType, route.providerName(), route.modelName(),
                    success ? "OK" : "FAIL", elapsed, cb.getState());

            return result;

        } catch (CallNotPermittedException e) {
            log.warn("[LLMInvoke] {} 熔断拒绝调用", route.providerName());
            LangChainResponse r = fallbackResponse(request, route, "CircuitBreaker OPEN — 熔断拒绝调用");
            r.setTraceId(traceId(request));
            r.setFallback(true);
            r.setBizType(bizType.name());
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            metrics.recordInvokeDuration(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            return r;

        } catch (RequestNotPermitted e) {
            log.warn("[LLMInvoke] {} 限流拒绝调用", route.providerName());
            LangChainResponse r = LangChainResponse.fail("RateLimited: " + e.getMessage(), request.getProvider());
            r.setTraceId(traceId(request));
            r.setFallback(true);
            r.setBizType(bizType.name());
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            metrics.recordInvokeDuration(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            return r;

        } catch (Exception e) {
            log.error("[LLMInvoke] {} / {} 异常: {}", route.providerName(), route.modelName(), e.getMessage(), e);
            LangChainResponse r = fallbackResponse(request, route, e.getMessage());
            r.setTraceId(traceId(request));
            r.setFallback(true);
            r.setBizType(bizType.name());
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            metrics.recordInvokeDuration(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            return r;
        }
    }

    /**
     * 流式调用 — 流式不做重试/熔断，在 RateLimiter 层保护 (provider + bizType)。
     */
    public void invokeStream(LangChainRequest request,
                             java.util.function.Consumer<String> chunkConsumer,
                             Runnable onComplete,
                             java.util.function.Consumer<Throwable> onError) {
        BizType bizType = BizType.from(request.getBizType());
        request.setBizType(bizType.name());
        RouteResult route = resolveRoute(request);
        if (!route.found()) {
            onError.accept(new RuntimeException(route.error()));
            return;
        }
        if (request.getTraceId() == null) {
            request.setTraceId(TraceIdFilter.currentTraceId());
        }

        String rateName = "llm-rate-" + route.providerName();
        String bizRateName = "llm-biz-rate-" + bizType.name().toLowerCase(Locale.ROOT) + "-" + route.providerName();
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(rateName);
        RateLimiter bizRateLimiter = rateLimiterRegistry.rateLimiter(bizRateName);

        if (!rateLimiter.acquirePermission() || !bizRateLimiter.acquirePermission()) {
            onError.accept(new RuntimeException("RateLimited"));
            return;
        }

        long start = System.currentTimeMillis();
        // 流式包装：统计 TTFT（首个 chunk 到达时间）并补记成功指标
        final AtomicLong ttft = new AtomicLong(-1);
        java.util.function.Consumer<String> wrappedChunk = chunk -> {
            if (ttft.get() < 0) {
                ttft.set(System.currentTimeMillis() - start);
                metrics.recordTtft(bizType.name(), route.providerName(), route.modelName(), ttft.get());
            }
            chunkConsumer.accept(chunk);
        };
        Runnable wrappedComplete = () -> {
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), true, elapsed);
            onComplete.run();
        };
        try {
            route.strategy().invokeStream(request, wrappedChunk, wrappedComplete,
                    throwable -> {
                        long elapsed = System.currentTimeMillis() - start;
                        metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
                        onError.accept(throwable);
                    });
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            metrics.recordInvoke(bizType.name(), route.providerName(), route.modelName(), false, elapsed);
            onError.accept(e);
        }
    }

    // ── 内部方法 ──────────────────────────────────────────

    /**
     * 列出所有已注册的 LLM 提供商信息。
     *
     * @return 每个 Map 含 name / type / baseUrl / enabled / modelCount
     */
    public List<Map<String, Object>> listProviders() {
        return registry.listProviderNames().stream()
                .map(registry::getProvider)
                .filter(Objects::nonNull)
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getName());
                    m.put("type", p.getInvokeType());
                    m.put("baseUrl", p.getBaseUrl());
                    m.put("enabled", p.isEnabled());
                    m.put("modelCount", p.getModels() != null ? p.getModels().size() : 0);
                    return m;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("name")))
                .collect(Collectors.toList());
    }

    /**
     * 实际调用 (不带任何弹性包装)。
     */
    private LangChainResponse invokeRaw(LangChainRequest request, RouteResult route) {
        log.debug("[invokeRaw] provider={} model={} strategy={}", route.providerName(), route.modelName(),
                route.strategy() == null ? "null" : route.strategy().getClass().getSimpleName());
        return route.strategy().invoke(request);
    }

    /**
     * 熔断/降级时的回退响应。
     */
    private LangChainResponse fallbackResponse(LangChainRequest request, RouteResult route, String reason) {
        log.warn("[LLMInvoke] fallback → {} (reason: {})", route.providerName(),
                reason.length() > 200 ? reason.substring(0, 200) : reason);

        // 请求携带动态配置(extra)时不做内部故障转移：
        // extra 的 baseUrl/apiKey 仅对调用方指定的 provider 有效，跨 provider 复用会串 key（如 qwen 的地址+deepseek 的模型导致 401/404），
        // 由调用方(chat-core)自行回退直连，路由与回退逻辑更完整。
        if (request.getExtra() != null && !request.getExtra().isEmpty()) {
            return LangChainResponse.fail(reason, request.getProvider());
        }

        // 尝试切换备用 provider
        if (route.providerName() != null) {
            for (String altName : registry.listProviderNames()) {
                if (altName.equalsIgnoreCase(route.providerName())) continue;
                RouteResult altRoute = registry.resolve(altName, null);
                if (altRoute.found()) {
                    log.warn("[LLMInvoke] 故障转移: {} → {}", route.providerName(), altRoute.providerName());
                    try {
                        // 复制请求并切换为备用 provider 的模型
                        LangChainRequest altReq = new LangChainRequest();
                        altReq.setProvider(altRoute.providerName());
                        altReq.setModel(altRoute.modelName());
                        altReq.setMessages(request.getMessages());
                        altReq.setSystemPrompt(request.getSystemPrompt());
                        altReq.setTemperature(request.getTemperature());
                        altReq.setMaxTokens(request.getMaxTokens());
                        altReq.setTopP(request.getTopP());
                        altReq.setStop(request.getStop());
                        altReq.setTools(request.getTools());
                        altReq.setToolChoice(request.getToolChoice());
                        altReq.setResponseFormat(request.getResponseFormat());
                        altReq.setStreamReasoning(request.getStreamReasoning());
                        altReq.setExtra(request.getExtra());
                        altReq.setStream(request.getStream());
                        altReq.setTraceId(request.getTraceId());
                        altReq.setBizType(request.getBizType());

                        LangChainResponse result = altRoute.strategy().invoke(altReq);
                        result.setFallback(true);
                        return result;
                    } catch (Exception e) {
                        log.warn("[LLMInvoke] 备用 provider {} 也失败了: {}", altRoute.providerName(), e.getMessage());
                    }
                }
            }
        }

        return LangChainResponse.fail(reason, request.getProvider());
    }

    private RouteResult resolveRoute(LangChainRequest request) {
        return registry.resolve(request.getProvider(), request.getModel());
    }

    private String traceId(LangChainRequest request) {
        if (request.getTraceId() != null) return request.getTraceId();
        return TraceIdFilter.currentTraceId();
    }
}
