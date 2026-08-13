package com.example.chat.observability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CircuitBreaker 真实行为断言（内存熔断状态机）：
 * 连续 5 次失败 CLOSED→OPEN 拒绝请求、成功恢复 CLOSED、provider 独立、状态上报。
 */
class CircuitBreakerTest {

    private final CircuitBreaker breaker = new CircuitBreaker();

    @Test
    void initial_stateAllowsRequest() {
        assertTrue(breaker.allowRequest("qwen"));
    }

    @Test
    void failuresBelowThreshold_stillAllows() {
        // 4 次失败 < 阈值 5，仍放行
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

        assertFalse(breaker.allowRequest("qwen"), "连续 5 次失败后应熔断拒绝");
        assertTrue(breaker.getAllStatus().get("qwen").contains("OPEN"),
                "状态应含 OPEN, got: " + breaker.getAllStatus());
    }

    @Test
    void recordSuccess_resetsToClosed() {
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure("qwen");
        }
        assertFalse(breaker.allowRequest("qwen"));

        breaker.recordSuccess("qwen");

        assertTrue(breaker.allowRequest("qwen"));
        assertTrue(breaker.getAllStatus().get("qwen").contains("CLOSED"));
    }

    @Test
    void state_isIsolatedPerProvider() {
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure("qwen");
        }

        assertFalse(breaker.allowRequest("qwen"));
        assertTrue(breaker.allowRequest("deepseek"), "其他 provider 不受影响");
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
        assertTrue(breaker.getAllStatus().get("doubao").contains("failures=1"));
    }

    @Test
    void getAllStatus_reportsFailureCount() {
        breaker.recordFailure("qwen");
        breaker.recordFailure("qwen");

        Map<String, String> status = breaker.getAllStatus();
        assertTrue(status.get("qwen").contains("failures=2"), "应报告失败次数, got: " + status);
    }
}
