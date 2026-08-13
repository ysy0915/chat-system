package com.example.chat.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CoreBusinessMetrics 真实行为断言（SimpleMeterRegistry）：
 * 漏斗分层命中/回退计数与耗时、工作流启动（parallel/degraded）、收敛（success/failed）。
 */
class CoreBusinessMetricsTest {

    private MeterRegistry registry = new SimpleMeterRegistry();
    private CoreBusinessMetrics metrics = new CoreBusinessMetrics();

    private void bind() {
        metrics.bindTo(registry);
    }

    private Counter counter(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        assertNotNull(c, "计数器应存在: " + name);
        return c;
    }

    @Test
    void funnelHit_recordsCounterByLayer() {
        bind();

        metrics.recordFunnelHit("L1", 1);
        metrics.recordFunnelHit("L1", 2);
        metrics.recordFunnelHit("L2", 10);
        metrics.recordFunnelHit("L3", 100);
        metrics.recordFunnelHit("FALLBACK", 200);

        assertEquals(2, counter("core.intent.funnel.hits", "layer", "L1").count());
        assertEquals(1, counter("core.intent.funnel.hits", "layer", "L2").count());
        assertEquals(1, counter("core.intent.funnel.hits", "layer", "L3").count());
        assertEquals(1, counter("core.intent.funnel.hits", "layer", "FALLBACK").count());
    }

    @Test
    void funnelHit_recordsLatencyTimer() {
        bind();

        metrics.recordFunnelHit("L2", 35);

        Timer timer = registry.find("core.intent.funnel.latency").tags("layer", "L2").timer();
        assertNotNull(timer, "latency Timer 应存在");
        assertEquals(1, timer.count());
        assertEquals(35, (long) timer.totalTime(TimeUnit.MILLISECONDS));
    }

    @Test
    void workflowStarted_recordsParallelAndDegraded() {
        bind();

        metrics.recordWorkflowStarted("parallel");
        metrics.recordWorkflowStarted("degraded");
        metrics.recordWorkflowStarted("degraded");

        assertEquals(1, counter("core.agent.workflow.started", "status", "parallel").count());
        assertEquals(2, counter("core.agent.workflow.started", "status", "degraded").count());
    }

    @Test
    void workflowConverged_recordsSuccessAndFailed() {
        bind();

        metrics.recordWorkflowConverged("success");
        metrics.recordWorkflowConverged("failed");

        assertEquals(1, counter("core.agent.workflow.converged", "status", "success").count());
        assertEquals(1, counter("core.agent.workflow.converged", "status", "failed").count());
    }

    @Test
    void beforeBind_registryIsNull_operationsAreNoop() {
        // 未绑定注册表（如纯单测）时安全空操作，不抛异常
        metrics.recordFunnelHit("L1", 1);
        metrics.recordWorkflowStarted("parallel");
        metrics.recordWorkflowConverged("success");

        assertTrue(registry.getMeters().isEmpty(), "未 bindTo 时不应注册任何 meter");
    }

    @Test
    void nullTag_valueFallsBackToUnknown() {
        bind();

        metrics.recordFunnelHit(null, 5);
        metrics.recordWorkflowStarted(null);

        assertEquals(1, counter("core.intent.funnel.hits", "layer", "unknown").count());
        assertEquals(1, counter("core.agent.workflow.started", "status", "unknown").count());
    }
}
