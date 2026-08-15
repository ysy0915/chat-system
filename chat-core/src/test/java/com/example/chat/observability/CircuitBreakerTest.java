package com.example.chat.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CircuitBreaker（Resilience4j 驱动）行为断言：
 * 滑动窗口失败率触发 OPEN、半开放探测成功恢复 CLOSED、provider 独立、状态上报。
 */
class CircuitBreakerTest {

    /** 测试用 registry：waitDuration 置 1ms（最小合法值），便于单测中验证 OPEN→HALF_OPEN→CLOSED 恢复链路 */
    private final CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerRegistry.of(
            CircuitBreakerConfig.custom()
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMillis(1))
                    .permittedNumberOfCallsInHalfOpenState(1)
                    .slidingWindowSize(10)
                    .minimumNumberOfCalls(5)
                    .build()));

    @Test
    void initial_stateAllowsRequest() {
        assertTrue(breaker.allowRequest("qwen"));
    }

    @Test
    void failuresBelowThreshold_stillAllows() {
        // 4 次失败，未达 minimumNumberOfCalls(5)，不参与失败率统计，仍放行
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure("qwen");
        }

        assertTrue(breaker.allowRequest("qwen"));
    }

    @Test
    void reachingThreshold_opensAndRejects() {
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure("qwen");
        }

        assertFalse(breaker.allowRequest("qwen"), "失败率达到阈值后应熔断拒绝");
        assertTrue(breaker.getAllStatus().get("llm-qwen").contains("OPEN"),
                "状态应含 OPEN, got: " + breaker.getAllStatus());
    }

    @Test
    void halfOpen_success_resetsToClosed() throws InterruptedException {
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure("qwen");
        }
        assertFalse(breaker.allowRequest("qwen"), "OPEN 状态应拒绝请求");

        // 等冷却(1ms)过后状态机转 HALF_OPEN，放行 1 次探测请求
        Thread.sleep(10);
        assertTrue(breaker.allowRequest("qwen"), "半开放应放行探测请求");
        breaker.recordSuccess("qwen");

        assertTrue(breaker.allowRequest("qwen"));
        assertTrue(breaker.getAllStatus().get("llm-qwen").contains("CLOSED"));
    }

    @Test
    void state_isIsolatedPerProvider() {
        // 独立 registry：冷却时间足够长（1s），避免 5 次失败后 allowRequest 因 1ms 冷却
        // 已过而立即转 HALF_OPEN 放行探测请求（与 halfOpen 测试共享极短冷却会时序敏感）
        CircuitBreaker isolated = new CircuitBreaker(CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(1))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .slidingWindowSize(10)
                        .minimumNumberOfCalls(5)
                        .build()));
        for (int i = 0; i < 5; i++) {
            isolated.recordFailure("qwen");
        }

        assertFalse(isolated.allowRequest("qwen"));
        assertTrue(isolated.allowRequest("deepseek"), "其他 provider 不受影响");
    }

    @Test
    void recordSuccessOnUnknownProvider_isNoOp() {
        // 未记录过失败的 provider 调 recordSuccess 不应崩溃
        assertDoesNotThrow(() -> breaker.recordSuccess("doubao"));
        assertTrue(breaker.allowRequest("doubao"));
    }

    @Test
    void recordFailureOnNewProvider_createsState() {
        breaker.recordFailure("doubao");

        assertEquals(1, breaker.getAllStatus().size());
        assertTrue(breaker.getAllStatus().get("llm-doubao").contains("calls=1"));
    }

    @Test
    void getAllStatus_reportsCallCount() {
        breaker.recordFailure("qwen");
        breaker.recordFailure("qwen");

        Map<String, String> status = breaker.getAllStatus();
        assertTrue(status.get("llm-qwen").contains("calls=2"), "应报告调用次数, got: " + status);
    }
}
