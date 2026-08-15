package com.example.chat.agent.planner;

import com.example.chat.agent.protocol.SubAgentPlan;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.workflow.SubTaskProducer;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ChatProcessor;
import com.example.chat.service.LLMInvoker;
import com.example.chat.service.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AgentWorkflowOrchestrator 工作流启动/回滚聚焦测试。
 *
 * <p>覆盖两类此前缺失的关键可靠性路径：</p>
 * <ul>
 *   <li>P1-1 部分任务分发失败：中途 sendTask 抛异常 → 回滚 Redis 状态（delete plan 相关 key），
 *       避免 plan 永久卡满 30min TTL、已发子任务白执行；</li>
 *   <li>permit 生命周期：启动失败/降级时 finally 释放限流许可，不泄漏。</li>
 * </ul>
 */
class AgentWorkflowOrchestratorTest {

    private TaskPlanner taskPlanner;
    private SubTaskProducer subTaskProducer;
    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    @SuppressWarnings("unchecked")
    private ZSetOperations<String, String> zsetOps;
    private BroadcastService broadcastService;
    private LLMInvoker llmInvoker;
    private LlmConfigProperties llmConfig;
    private ChatProcessor chatProcessor;
    private ModelRouter modelRouter;
    private AgentWorkflowOrchestrator orchestrator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskPlanner = mock(TaskPlanner.class);
        subTaskProducer = mock(SubTaskProducer.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        broadcastService = mock(BroadcastService.class);
        llmInvoker = mock(LLMInvoker.class);
        llmConfig = mock(LlmConfigProperties.class);
        chatProcessor = mock(ChatProcessor.class);
        modelRouter = mock(ModelRouter.class);

        orchestrator = new AgentWorkflowOrchestrator(
                taskPlanner, subTaskProducer, redisTemplate, broadcastService,
                llmInvoker, llmConfig, new ObjectMapper(), chatProcessor, modelRouter);
    }

    private SubAgentPlan planOf(int taskCount) {
        SubAgentPlan plan = new SubAgentPlan();
        plan.planId = "plan-1";
        for (int i = 0; i < taskCount; i++) {
            SubAgentTask t = new SubAgentTask();
            t.taskId = "plan-1-t" + i;
            t.planId = "plan-1";
            t.instructions = "子任务 " + i;
            plan.tasks.add(t);
        }
        return plan;
    }

    // ═══════════ P1-1 部分任务分发失败回滚 ═══════════

    @Test
    @DisplayName("部分任务分发失败：回滚 Redis 状态，返回 false，不卡 plan")
    void partialSendFailureRollsBackPlan() throws Exception {
        SubAgentPlan plan = planOf(3);
        when(taskPlanner.shouldDecompose(anyString())).thenReturn(true);
        // 许可获取成功（redis execute 返回 1）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(taskPlanner.buildPlan(anyString(), any(), any(), any())).thenReturn(plan);
        // 第 2 条 sendTask 抛异常，模拟中途分发失败
        doNothing().when(subTaskProducer).sendTask(any(SubAgentTask.class));
        doThrow(new IllegalStateException("MQ 断连"))
                .when(subTaskProducer).sendTask(argThat(t -> "plan-1-t1".equals(t.taskId)));

        boolean started = orchestrator.tryParallelWorkflow(
                "req-1", 100L, "请分析市场、调研竞品、设计方案", new ModelConfig(), 0.7);

        assertFalse(started);
        // 回滚：删除了 plan 相关 Redis key（含 meta/total/received 等）
        verify(redisTemplate).delete(anyList());
        // 从 Reconciler 索引移除
        verify(zsetOps).remove(eq("agent:reconciler:plans"), eq("plan-1"));
        // 前端收到错误广播
        verify(broadcastService).broadcast(contains("/topic/user.100"), any());
    }

    @Test
    @DisplayName("部分失败回滚后 permit 被释放（finally !started）")
    void partialSendFailureReleasesPermit() throws Exception {
        SubAgentPlan plan = planOf(2);
        when(taskPlanner.shouldDecompose(anyString())).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(taskPlanner.buildPlan(anyString(), any(), any(), any())).thenReturn(plan);
        doThrow(new IllegalStateException("MQ 断连"))
                .when(subTaskProducer).sendTask(any(SubAgentTask.class));

        orchestrator.tryParallelWorkflow(
                "req-2", 100L, "请分析市场、调研竞品", new ModelConfig(), 0.7);

        // 释放许可：RELEASE_SCRIPT 被再次 execute（acquire 1 次 + release 1 次）
        verify(redisTemplate, times(2)).execute(any(RedisScript.class), anyList(), anyString());
    }

    // ═══════════ 并发过载降级 ═══════════

    @Test
    @DisplayName("许可获取失败（过载）：降级普通流程，不启动工作流")
    void overloadDegradesWithoutStarting() throws Exception {
        when(taskPlanner.shouldDecompose(anyString())).thenReturn(true);
        // 许可获取失败（redis execute 返回 0）
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(0L);

        boolean started = orchestrator.tryParallelWorkflow(
                "req-3", 100L, "请分析市场、调研竞品", new ModelConfig(), 0.7);

        assertFalse(started);
        // 未分发任何子任务
        verify(subTaskProducer, never()).sendTask(any());
        // 未回滚（未写状态，无需清理）
        verify(redisTemplate, never()).delete(anyList());
    }

    // ═══════════ 计划生成失败降级 ═══════════

    @Test
    @DisplayName("计划生成失败（buildPlan 返回 null）：降级原流程")
    void planNullDegrades() throws Exception {
        when(taskPlanner.shouldDecompose(anyString())).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(1L);
        when(taskPlanner.buildPlan(anyString(), any(), any(), any())).thenReturn(null);

        boolean started = orchestrator.tryParallelWorkflow(
                "req-4", 100L, "请分析市场、调研竞品", new ModelConfig(), 0.7);

        assertFalse(started);
        verify(subTaskProducer, never()).sendTask(any());
    }
}
