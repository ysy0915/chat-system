package com.example.chat.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Resilience4j 的熔断器（按 LLM provider 分实例）。
 *
 * <p>配置来源于 chat-common 的 {@code ResilienceConfig} 默认规则：
 * 滑动窗口 10 次、最少 5 次调用、失败率 ≥50% 熔断、冷却 30s、半开放行 3 次探测。
 * 相比自研"连续失败 N 次"实现，Resilience4j 使用滑动窗口失败率统计，
 * 且熔断指标自动上报 Micrometer/Prometheus（{@code resilience4j_circuitbreaker_*}）。</p>
 *
 * <p>对外保持原 {@code allowRequest / recordSuccess / recordFailure / getAllStatus}
 * 方法签名，调用方（LLMInvoker）无感知。</p>
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreaker(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    private io.github.resilience4j.circuitbreaker.CircuitBreaker breaker(String provider) {
        return registry.circuitBreaker("llm-" + provider);
    }

    /**
     * 判断指定 provider 是否允许请求通过
     * @param provider 模型提供商标识（qwen/deepseek/doubao等）
     * @return true=允许通过，false=熔断中拒绝
     */
    public boolean allowRequest(String provider) {
        boolean allowed = breaker(provider).tryAcquirePermission();
        if (!allowed) {
            log.warn("[CircuitBreaker] provider={} 已熔断，拒绝请求", provider);
        }
        return allowed;
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String provider) {
        breaker(provider).onSuccess(0, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录失败
     */
    public void recordFailure(String provider) {
        breaker(provider).onError(0, TimeUnit.MILLISECONDS,
                new RuntimeException("llm-" + provider + " call failed"));
    }

    /**
     * 获取所有 provider 的熔断状态（用于监控）
     */
    public Map<String, String> getAllStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        registry.getAllCircuitBreakers().forEach(cb -> {
            var metrics = cb.getMetrics();
            status.put(cb.getName(),
                    cb.getState().name()
                            + "(failureRate=" + Math.round(metrics.getFailureRate())
                            + "% calls=" + metrics.getNumberOfBufferedCalls()
                            + " notPermitted=" + metrics.getNumberOfNotPermittedCalls() + ")");
        });
        return status;
    }
}
