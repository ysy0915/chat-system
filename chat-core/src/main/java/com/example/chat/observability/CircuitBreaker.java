package com.example.chat.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简易熔断器（无第三方依赖）
 *
 * 工作原理：
 * - CLOSED（正常）：请求正常通过，记录失败次数
 * - OPEN（熔断）：连续失败达到阈值，直接拒绝请求（快速失败）
 * - HALF_OPEN（半开）：冷却时间过后，放行1个探测请求；成功则恢复CLOSED，失败则重回OPEN
 *
 * 配置：
 * - failureThreshold：连续失败阈值（默认5次）
 * - recoveryTimeout：熔断恢复冷却时间（默认60秒）
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private static final int FAILURE_THRESHOLD = 5;
    private static final long RECOVERY_TIMEOUT_MS = 60_000L;

    /** 每个 provider 一个独立的熔断状态 */
    private final ConcurrentHashMap<String, BreakerState> breakers = new ConcurrentHashMap<>();

    private static class BreakerState {
        volatile String state = "CLOSED"; // CLOSED / OPEN / HALF_OPEN
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        volatile long openedAt = 0; // 熔断打开的时间戳

        /**
         * 判断是否允许请求通过
         */
        boolean allowRequest() {
            if ("CLOSED".equals(state)) {
                return true;
            }
            if ("OPEN".equals(state)) {
                // 检查是否已过冷却期
                if (System.currentTimeMillis() - openedAt > RECOVERY_TIMEOUT_MS) {
                    state = "HALF_OPEN";
                    log.info("[CircuitBreaker] 状态切换 OPEN -> HALF_OPEN，放行探测请求");
                    return true;
                }
                return false; // 熔断中，拒绝请求
            }
            if ("HALF_OPEN".equals(state)) {
                // 半开状态只放行1个探测请求（简化实现：都放行，靠 onSuccess/onFailure纠正）
                return true;
            }
            return true;
        }

        void recordSuccess() {
            if (!"CLOSED".equals(state)) {
                log.info("[CircuitBreaker] 状态切换 {} -> CLOSED", state);
            }
            state = "CLOSED";
            consecutiveFailures.set(0);
        }

        void recordFailure() {
            int failures = consecutiveFailures.incrementAndGet();
            if ("HALF_OPEN".equals(state)) {
                // 半开状态下失败，立即重回OPEN
                state = "OPEN";
                openedAt = System.currentTimeMillis();
                log.warn("[CircuitBreaker] HALF_OPEN 探测失败，重回 OPEN");
            } else if (failures >= FAILURE_THRESHOLD && "CLOSED".equals(state)) {
                state = "OPEN";
                openedAt = System.currentTimeMillis();
                log.warn("[CircuitBreaker] 连续失败 {} 次，状态切换 CLOSED -> OPEN", failures);
            }
        }

        String getStatus() {
            return state + "(failures=" + consecutiveFailures.get() + ")";
        }
    }

    /**
     * 判断指定 provider 是否允许请求通过
     * @param provider 模型提供商标识（qwen/deepseek/doubao等）
     * @return true=允许通过，false=熔断中拒绝
     */
    public boolean allowRequest(String provider) {
        BreakerState breaker = breakers.computeIfAbsent(provider, k -> new BreakerState());
        boolean allowed = breaker.allowRequest();
        if (!allowed) {
            log.warn("[CircuitBreaker] provider={} 已熔断，拒绝请求", provider);
        }
        return allowed;
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String provider) {
        BreakerState breaker = breakers.get(provider);
        if (breaker != null) {
            breaker.recordSuccess();
        }
    }

    /**
     * 记录失败
     */
    public void recordFailure(String provider) {
        BreakerState breaker = breakers.computeIfAbsent(provider, k -> new BreakerState());
        breaker.recordFailure();
    }

    /**
     * 获取所有 provider 的熔断状态（用于监控）
     */
    public Map<String, String> getAllStatus() {
        Map<String, String> status = new java.util.LinkedHashMap<>();
        breakers.forEach((provider, breaker) -> status.put(provider, breaker.getStatus()));
        return status;
    }
}
