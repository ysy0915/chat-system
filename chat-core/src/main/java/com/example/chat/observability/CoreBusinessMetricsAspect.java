package com.example.chat.observability;

import com.example.chat.intent.funnel.IntentFunnelEngine.FunnelRecognizeResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * <h2>chat-core 业务指标切面（横切埋点）</h2>
 *
 * <p>把业务级指标采集从业务类中抽离为切面（AOP），业务类零侵入：
 * 切点声明式声明，埋点逻辑集中在本类，指标增减/监控目标变更只改这里。</p>
 *
 * <pre>
 *   切点                               指标                                 取值来源
 *   IntentFunnelEngine.recognize        core.intent.funnel.hits/latency      返回值 source()/latencyMs()
 *   AgentWorkflowOrchestrator.tryParallelWorkflow  core.agent.workflow.started 返回值 boolean
 *   AgentWorkflowOrchestrator.converge  core.agent.workflow.converged         正常返回/抛出异常
 * </pre>
 *
 * <p>三个切点均为跨类调用（ChatProcessor / SubTaskResultCollector / WorkflowReconciler），
 * Spring AOP 代理全部生效，无同类自调用绕代理问题。</p>
 *
 * <p>依赖 chat-core pom 的 {@code spring-boot-starter-aop}；指标注册表判空与安全降级
 * 见 {@link CoreBusinessMetrics}。</p>
 */
@Aspect
@Component
public class CoreBusinessMetricsAspect {

    private final CoreBusinessMetrics metrics;

    public CoreBusinessMetricsAspect(CoreBusinessMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 意图漏斗：从返回值读取分层（RULES/CONTEXT/LLM/FALLBACK）与耗时，无需侵入方法内部。
     */
    @Around("execution(* com.example.chat.intent.funnel.IntentFunnelEngine.recognize(..))")
    public Object funnel(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (result instanceof FunnelRecognizeResult fr) {
            metrics.recordFunnelHit(mapLayer(fr.source()), fr.latencyMs());
        }
        return result;
    }

    /**
     * 工作流启动：boolean 返回值天然映射 parallel/degraded，
     * 覆盖并发过载降级 / 计划失败降级 / 启动异常降级三条路径。
     */
    @Around("execution(* com.example.chat.agent.planner.AgentWorkflowOrchestrator.tryParallelWorkflow(..))")
    public Object workflowStarted(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        metrics.recordWorkflowStarted(Boolean.TRUE.equals(result) ? "parallel" : "degraded");
        return result;
    }

    /**
     * 收敛：正常返回记 success，抛异常记 failed 后不重抛——保持 {@code converge}
     * 方法内部消化异常的对外语义（调用方 WorkflowReconciler/ResultCollector 感知不变）。
     */
    @Around("execution(* com.example.chat.agent.planner.AgentWorkflowOrchestrator.converge(..))")
    public Object converge(ProceedingJoinPoint pjp) throws Throwable {
        try {
            Object result = pjp.proceed();
            metrics.recordWorkflowConverged("success");
            return result;
        } catch (Throwable t) {
            metrics.recordWorkflowConverged("failed");
            return null;
        }
    }

    /** FunnelRecognizeResult.source() → 指标 layer 标签（unknown 兜底 FALLBACK） */
    static String mapLayer(String source) {
        return switch (source == null ? "" : source) {
            case "RULES" -> CoreBusinessMetrics.LAYER_L1;
            case "CONTEXT" -> CoreBusinessMetrics.LAYER_L2;
            case "LLM" -> CoreBusinessMetrics.LAYER_L3;
            default -> CoreBusinessMetrics.LAYER_FALLBACK;
        };
    }
}
