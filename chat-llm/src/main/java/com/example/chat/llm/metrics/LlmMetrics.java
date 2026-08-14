package com.example.chat.llm.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>LLM 模块自定义指标</h2>
 *
 * <p>暴露给 Prometheus 的指标，覆盖 LLM 调用和 Graph 执行两个维度。</p>
 *
 * <h3>度量维度</h3>
 * <pre>
 *   llm.invoke.total       — 调用计数器 (tag: biz, provider, model, status)
 *   llm.invoke.duration    — 调用耗时 (tag: biz, provider, model, status)
 *   llm.invoke.tokens      — Token 消耗 (tag: biz, provider, model, type)
 *
 *   llm.graph.executions   — Graph 执行计数器 (tag: entryPoint, status)
 *   llm.graph.duration     — Graph 执行耗时 (tag: entryPoint, status)
 *   llm.graph.node.duration— 节点执行耗时 (tag: nodeId, status)
 *   llm.graph.steps        — 步骤数 (tag: entryPoint)
 *
 *   llm.circuit.state      — 断路器状态 (tag: name, state)
 *   llm.provider.healthy   — 提供商健康 (tag: provider)
 * </pre>
 */
@Component
public class LlmMetrics {

    // 注册表引用（构造器注入，保证非空）
    private final MeterRegistry registry;
    private final AtomicLong circuitStateGauge = new AtomicLong(0);

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── Counter ─────────────────────────────────────────────

    public void recordInvoke(String bizType, String provider, String model, boolean success, long durationMs) {
        if (registry == null) return;
        String status = success ? "success" : "failure";
        Counter.builder("llm.invoke.total")
                .description("LLM invoke counter")
                .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "status", status)
                .register(registry)
                .increment();
    }

    public void recordInvokeDuration(String bizType, String provider, String model, boolean success, long durationMs) {
        if (registry == null) return;
        String status = success ? "success" : "failure";
        Timer.builder("llm.invoke.duration")
                .description("LLM invoke duration")
                .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "status", status)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordTokens(String bizType, String provider, String model, int promptTokens, int completionTokens) {
        if (registry == null) return;
        Counter.builder("llm.invoke.tokens")
                .description("LLM token usage")
                .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "type", "prompt")
                .register(registry)
                .increment(promptTokens);
        Counter.builder("llm.invoke.tokens")
                .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "type", "completion")
                .register(registry)
                .increment(completionTokens);
    }

    /** 上下文缓存 token 用量 (prompt_cache_hit/miss_tokens，type=cache_hit/cache_miss) */
    public void recordCacheTokens(String bizType, String provider, String model, int cacheHitTokens, int cacheMissTokens) {
        if (registry == null) return;
        if (cacheHitTokens > 0) {
            Counter.builder("llm.invoke.tokens")
                    .description("LLM token usage")
                    .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "type", "cache_hit")
                    .register(registry)
                    .increment(cacheHitTokens);
        }
        if (cacheMissTokens > 0) {
            Counter.builder("llm.invoke.tokens")
                    .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model), "type", "cache_miss")
                    .register(registry)
                    .increment(cacheMissTokens);
        }
    }

    /** 首 token 延迟 (TTFT, 流式) */
    public void recordTtft(String bizType, String provider, String model, long ttftMs) {
        if (registry == null) return;
        Timer.builder("llm.invoke.ttft")
                .description("LLM time to first token (streaming)")
                .tags("biz", nvl(bizType), "provider", nvl(provider), "model", nvl(model))
                .register(registry)
                .record(ttftMs, TimeUnit.MILLISECONDS);
    }

    // ── Graph ───────────────────────────────────────────────

    public void recordGraphExecution(String entryPoint, boolean success, long durationMs) {
        if (registry == null) return;
        String status = success ? "success" : "failure";
        Counter.builder("llm.graph.executions")
                .description("Graph execution counter")
                .tags("entryPoint", nvl(entryPoint), "status", status)
                .register(registry)
                .increment();
        Timer.builder("llm.graph.duration")
                .description("Graph execution duration")
                .tags("entryPoint", nvl(entryPoint), "status", status)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordNodeDuration(String nodeId, boolean success, long durationMs) {
        if (registry == null) return;
        String status = success ? "success" : "failure";
        Timer.builder("llm.graph.node.duration")
                .description("Graph node execution duration")
                .tags("nodeId", nvl(nodeId), "status", status)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordGraphSteps(String entryPoint, int steps) {
        if (registry == null) return;
        DistributionSummary.builder("llm.graph.steps")
                .description("Graph execution steps")
                .tags("entryPoint", nvl(entryPoint))
                .register(registry)
                .record(steps);
    }

    // ── Circuit / Health ────────────────────────────────────

    public void recordCircuitState(String name, String state) {
        // state: CLOSED / OPEN / HALF_OPEN
        // 由 CircuitBreakerEventListener 异步更新
    }

    public void recordProviderHealth(String provider, boolean healthy) {
        if (registry == null) return;
        Gauge.builder("llm.provider.healthy", () -> healthy ? 1.0 : 0.0)
                .description("Provider health status")
                .tags("provider", nvl(provider))
                .register(registry);
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
