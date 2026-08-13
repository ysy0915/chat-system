package com.example.chat.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * <h2>chat-core 业务级指标</h2>
 *
 * <p>将业务运行质量暴露给 Prometheus（/actuator/prometheus），补足告警覆盖的业务维度。
 * 与 chat-llm 的 {@code LlmMetrics} 互补：LLM 侧已覆盖调用成本/图执行，本类覆盖核心 AI 编排侧。</p>
 *
 * <h3>度量维度</h3>
 * <pre>
 *   core.intent.funnel.hits      — 意图漏斗各层命中/回退计数 (tag: layer=L1|L2|L3|FALLBACK)
 *   core.intent.funnel.latency   — 漏斗识别耗时 (tag: layer)     → L1+L2 综合命中率可计算
 *
 *   core.agent.workflow.started  — Multi-Agent 工作流启动计数 (tag: status=parallel|degraded)
 *                                  degraded 为并发过载/计划失败降级走普通流程
 *   core.agent.workflow.converged— 工作流收敛完成计数 (tag: status=success|failed)
 * </pre>
 *
 * <p>对应业务告警（docs/prometheus-alert-rules.yml chat-system-business 组）：</p>
 * <ul>
 *   <li>IntentFunnelHitRateLow：L1+L2 综合命中率低于目标（阈值可调）；</li>
 *   <li>AgentWorkflowDegradeHigh：工作流降级率过高（并发过载/计划失败占比）；</li>
 *   <li>AgentWorkflowConvergeFail：收敛失败持续出现；</li>
 *   <li>LLMTokenSurge：LLM token 消耗激增（数据源为 chat-llm 的 llm.invoke.tokens）。</li>
 * </ul>
 *
 * <p>实现说明：经 chat-common 传递依赖获得 micrometer（micrometer-registry-prometheus 1.11.6，
 * 与 Boot 3.1.6 匹配），本类无需新增 Maven 依赖；无注册表时（纯单测）所有方法空操作安全。</p>
 */
@Component
public class CoreBusinessMetrics implements MeterBinder {

    /** 意图漏斗层标签 */
    public static final String LAYER_L1 = "L1";
    public static final String LAYER_L2 = "L2";
    public static final String LAYER_L3 = "L3";
    public static final String LAYER_FALLBACK = "FALLBACK";

    private MeterRegistry registry;

    // ── 意图漏斗 ─────────────────────────────────────────────

    /**
     * 记录一次漏斗识别命中（或回退），含耗时。
     *
     * @param layer     命中层 L1/L2/L3 或 FALLBACK
     * @param latencyMs 识别总耗时（毫秒）
     */
    public void recordFunnelHit(String layer, long latencyMs) {
        if (registry == null) return;
        Counter.builder("core.intent.funnel.hits")
                .description("Intent funnel hits by layer (L1/L2/L3/FALLBACK)")
                .tags("layer", nvl(layer))
                .register(registry)
                .increment();
        Timer.builder("core.intent.funnel.latency")
                .description("Intent funnel recognize latency by layer")
                .tags("layer", nvl(layer))
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    // ── Multi-Agent 工作流 ───────────────────────────────────

    /**
     * 记录一次并行工作流启动（或降级走普通流程）。
     *
     * @param status parallel=成功接管并行工作流；degraded=并发过载/计划失败降级
     */
    public void recordWorkflowStarted(String status) {
        if (registry == null) return;
        Counter.builder("core.agent.workflow.started")
                .description("Multi-Agent workflow start counter (parallel/degraded)")
                .tags("status", nvl(status))
                .register(registry)
                .increment();
    }

    /**
     * 记录一次工作流收敛结果。
     *
     * @param status success=收敛完成推送最终回答；failed=收敛异常
     */
    public void recordWorkflowConverged(String status) {
        if (registry == null) return;
        Counter.builder("core.agent.workflow.converged")
                .description("Multi-Agent workflow converge counter (success/failed)")
                .tags("status", nvl(status))
                .register(registry)
                .increment();
    }

    // ── MeterBinder ─────────────────────────────────────────

    @Override
    public void bindTo(MeterRegistry meterRegistry) {
        this.registry = meterRegistry;
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
