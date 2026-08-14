package com.example.chat.llm.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import com.example.chat.dto.LangChainResponse;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * <h2>Resilience4j 弹性能力配置</h2>
 *
 * <p>为每个 LLM 提供商独立创建 CircuitBreaker / Retry / RateLimiter / TimeLimiter，
 * 实现熔断降级、自动重试、限流、超时控制。</p>
 *
 * <h3>注册的 Resilience 组件</h3>
 * <pre>
 *   llm-cb-{provider}    → CircuitBreaker
 *   llm-retry-{provider} → Retry
 *   llm-rate-{provider}  → RateLimiter
 *   llm-time-{provider}  → TimeLimiter
 *
 *   全局:
 *   llm-cb-default       → CircuitBreaker (聚合视图)
 *   llm-retry-graph      → Retry (图执行)
 * </pre>
 */
@Configuration
public class ResilienceConfig {

    @Autowired
    private LLMConfig llmConfig;

    // ── CircuitBreaker ──────────────────────────────────────

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultCb = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)                // 50% 失败率触发熔断
                .slowCallRateThreshold(100)              // 慢调用阈值（不启用）
                .slowCallDurationThreshold(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)                    // 最近 20 次调用
                .minimumNumberOfCalls(10)                  // 最少 10 次调用才计算失败率
                .waitDurationInOpenState(Duration.ofSeconds(30)) // 熔断等待 30s
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(defaultCb);

        // 为每个提供商注册独立断路器
        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            registry.circuitBreaker("llm-cb-" + pc.getName(), defaultCb);
        }
        // 聚合默认断路器
        registry.circuitBreaker("llm-cb-default", defaultCb);

        return registry;
    }

    // ── Retry ───────────────────────────────────────────────

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig defaultRetry = RetryConfig.custom()
                .maxAttempts(3)                             // 含首次最多 3 次
                .waitDuration(Duration.ofMillis(500))
                .retryOnResult(r -> r instanceof LangChainResponse resp && !resp.isSuccess())
                .retryOnException(e -> true)                 // 所有异常都重试
                .failAfterMaxAttempts(true)
                .build();

        RetryRegistry registry = RetryRegistry.of(defaultRetry);

        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            registry.retry("llm-retry-" + pc.getName(), defaultRetry);
        }
        // 图执行全局重试
        RetryConfig graphRetry = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofSeconds(1))
                .retryOnException(e -> true)
                .failAfterMaxAttempts(true)
                .build();
        registry.retry("llm-retry-graph", graphRetry);

        return registry;
    }

    // ── RateLimiter ─────────────────────────────────────────

    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig defaultRate = RateLimiterConfig.custom()
                .limitForPeriod(10)                          // 每秒最多 10 次
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(5))      // 超时等待最大 5s
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(defaultRate);

        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            registry.rateLimiter("llm-rate-" + pc.getName(), defaultRate);
        }

        return registry;
    }

    // ── TimeLimiter ─────────────────────────────────────────

    @Bean
    public TimeLimiterRegistry timeLimiterRegistry() {
        TimeLimiterConfig defaultTime = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(llmConfig.getInvokeTimeoutSeconds()))
                .cancelRunningFuture(true)
                .build();

        TimeLimiterRegistry registry = TimeLimiterRegistry.of(defaultTime);

        for (LLMConfig.ProviderConfig pc : llmConfig.getProviders()) {
            registry.timeLimiter("llm-time-" + pc.getName(), defaultTime);
        }

        return registry;
    }
}
