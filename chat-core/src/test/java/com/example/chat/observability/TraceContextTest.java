package com.example.chat.observability;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TraceContext 真实行为断言（ThreadLocal 链路上下文）：
 * start 生成 8 位 traceId、get 同值、clear 清空、两次 start 不同、线程间隔离。
 */
class TraceContextTest {

    private final TraceContext context = new TraceContext();

    @Test
    void start_returnsEightCharTraceId() {
        String traceId = context.start();

        assertNotNull(traceId);
        assertEquals(8, traceId.length());
        assertTrue(traceId.matches("[0-9a-f]{8}"), "traceId 应为 8 位 hex, got: " + traceId);
    }

    @Test
    void startThenGet_returnsSameTraceId() {
        String traceId = context.start();

        assertEquals(traceId, context.get());
    }

    @Test
    void clear_makesGetReturnNull() {
        context.start();
        assertNotNull(context.get());

        context.clear();

        assertNull(context.get());
    }

    @Test
    void consecutiveStarts_generateDifferentIds() {
        String first = context.start();
        String second = context.start();

        assertNotEquals(first, second);
    }

    @Test
    void traceId_isIsolatedPerThread() throws Exception {
        context.start();
        AtomicReference<String> otherThreadValue = new AtomicReference<>("unset");

        Thread worker = new Thread(() -> otherThreadValue.set(context.get()));
        worker.start();
        worker.join();

        assertNull(otherThreadValue.get(), "其他线程不应看到本线程的 traceId");
        assertNotNull(context.get(), "本线程 traceId 不受影响");
    }
}
