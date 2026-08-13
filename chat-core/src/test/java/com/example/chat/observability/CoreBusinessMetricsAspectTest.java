package com.example.chat.observability;

import com.example.chat.intent.IntentResult;
import com.example.chat.intent.funnel.IntentFunnelEngine.FunnelRecognizeResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CoreBusinessMetricsAspect 真实行为断言（Mockito 模拟 JoinPoint）：
 * 三个切点的返回值/异常 → 指标记录映射，以及异常不重抛的对外语义。
 */
@ExtendWith(MockitoExtension.class)
class CoreBusinessMetricsAspectTest {

    @Mock
    private CoreBusinessMetrics metrics;

    @Mock
    private ProceedingJoinPoint pjp;

    private CoreBusinessMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new CoreBusinessMetricsAspect(metrics);
    }

    // ── funnel：source → layer 映射 + 耗时透传 ──

    @Test
    void funnel_mapsRuleSourceToL1() throws Throwable {
        FunnelRecognizeResult result = new FunnelRecognizeResult(IntentResult.unknown(), "RULES", 7L);
        when(pjp.proceed()).thenReturn(result);

        Object returned = aspect.funnel(pjp);

        assertEquals(result, returned, "切面应原样返回结果");
        verify(metrics).recordFunnelHit("L1", 7L);
    }

    @Test
    void funnel_mapsContextToL2_llmToL3() throws Throwable {
        when(pjp.proceed()).thenReturn(
                new FunnelRecognizeResult(IntentResult.unknown(), "CONTEXT", 40L));

        aspect.funnel(pjp);
        verify(metrics).recordFunnelHit("L2", 40L);

        when(pjp.proceed()).thenReturn(
                new FunnelRecognizeResult(IntentResult.unknown(), "LLM", 300L));
        aspect.funnel(pjp);
        verify(metrics).recordFunnelHit("L3", 300L);
    }

    @Test
    void funnel_mapsFallbackToFallbackLayer() throws Throwable {
        when(pjp.proceed()).thenReturn(
                new FunnelRecognizeResult(IntentResult.unknown(), "FALLBACK", 500L));

        aspect.funnel(pjp);

        verify(metrics).recordFunnelHit("FALLBACK", 500L);
    }

    @Test
    void funnel_nonFunnelReturn_doesNotRecord() throws Throwable {
        when(pjp.proceed()).thenReturn("not-a-funnel-result");

        aspect.funnel(pjp);

        verifyNoInteractions(metrics);
    }

    // ── workflowStarted：boolean 返回值 → parallel/degraded ──

    @Test
    void workflowStarted_trueMapsParallel() throws Throwable {
        when(pjp.proceed()).thenReturn(true);

        Object returned = aspect.workflowStarted(pjp);

        assertEquals(Boolean.TRUE, returned);
        verify(metrics).recordWorkflowStarted("parallel");
    }

    @Test
    void workflowStarted_falseMapsDegraded() throws Throwable {
        when(pjp.proceed()).thenReturn(false);

        aspect.workflowStarted(pjp);

        verify(metrics).recordWorkflowStarted("degraded");
    }

    // ── converge：正常返回 success / 异常 failed 且不重抛 ──

    @Test
    void converge_normalReturnRecordsSuccess() throws Throwable {
        when(pjp.proceed()).thenReturn(null);

        Object returned = aspect.converge(pjp);

        assertNull(returned);
        verify(metrics).recordWorkflowConverged("success");
    }

    @Test
    void converge_exceptionRecordsFailed_andDoesNotRethrow() throws Throwable {
        when(pjp.proceed()).thenThrow(new RuntimeException("converge boom"));

        Object returned = aspect.converge(pjp);

        assertNull(returned, "切面应吞掉异常，保持 converge 对外不抛语义");
        verify(metrics).recordWorkflowConverged("failed");
    }

    // ── mapLayer 边界 ──

    @Test
    void mapLayer_unknownSource_fallsBackToFallback() {
        assertEquals("L1", CoreBusinessMetricsAspect.mapLayer("RULES"));
        assertEquals("L2", CoreBusinessMetricsAspect.mapLayer("CONTEXT"));
        assertEquals("L3", CoreBusinessMetricsAspect.mapLayer("LLM"));
        assertEquals("FALLBACK", CoreBusinessMetricsAspect.mapLayer("FALLBACK"));
        assertEquals("FALLBACK", CoreBusinessMetricsAspect.mapLayer("UNKNOWN"));
        assertEquals("FALLBACK", CoreBusinessMetricsAspect.mapLayer(null));
    }
}
