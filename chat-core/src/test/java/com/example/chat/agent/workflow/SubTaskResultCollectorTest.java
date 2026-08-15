package com.example.chat.agent.workflow;

import com.example.chat.agent.planner.AgentWorkflowOrchestrator;
import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.service.BroadcastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.io.IOException;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubTaskResultCollector 结果收敛触发聚焦测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>结果到齐 → SETNX 收敛锁（使用共享 CONVERGE_LOCK_TTL，防收敛超时被重复触发）+ 异步收敛；</li>
 *   <li>结果未到齐 → 不触发收敛；</li>
 *   <li>处理异常 → nack requeue（结果丢失会导致收敛永远等不到，必须重试）。</li>
 * </ul>
 */
class SubTaskResultCollectorTest {

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;
    @SuppressWarnings("unchecked")
    private HashOperations<String, Object, Object> hashOps;
    @SuppressWarnings("unchecked")
    private ZSetOperations<String, String> zsetOps;
    private BroadcastService broadcastService;
    private AgentWorkflowOrchestrator orchestrator;
    private SubTaskResultCollector collector;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        hashOps = mock(HashOperations.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        broadcastService = mock(BroadcastService.class);
        orchestrator = mock(AgentWorkflowOrchestrator.class);
        collector = new SubTaskResultCollector(redisTemplate, new ObjectMapper(),
                broadcastService, orchestrator);
    }

    private SubAgentResult result(String planId, String taskId) {
        SubAgentResult r = new SubAgentResult();
        r.planId = planId;
        r.taskId = taskId;
        r.success = true;
        r.summary = "摘要";
        return r;
    }

    @Test
    @DisplayName("结果到齐：用共享 CONVERGE_LOCK_TTL 抢占收敛锁并触发收敛")
    void completeUsesSharedLockTtl() throws IOException {
        String planId = "plan-1";
        when(valueOps.increment(AgentWorkflowOrchestrator.keyReceived(planId))).thenReturn(3L);
        when(valueOps.get(AgentWorkflowOrchestrator.keyTotal(planId))).thenReturn("3");
        when(valueOps.get(AgentWorkflowOrchestrator.keyMeta(planId)))
                .thenReturn("{\"reqId\":\"req-1\",\"userId\":100}");
        // SETNX 锁成功
        when(valueOps.setIfAbsent(eq(AgentWorkflowOrchestrator.keyLock(planId)),
                eq("1"), eq(AgentWorkflowOrchestrator.CONVERGE_LOCK_TTL))).thenReturn(true);

        Channel channel = mock(Channel.class);
        collector.onResult(result(planId, "t2"), channel, 5L);

        // 使用共享锁 TTL（与 Reconciler 一致，防止收敛超时被重复触发）
        verify(valueOps).setIfAbsent(eq(AgentWorkflowOrchestrator.keyLock(planId)),
                eq("1"), eq(AgentWorkflowOrchestrator.CONVERGE_LOCK_TTL));
        verify(channel).basicAck(5L, false);
    }

    @Test
    @DisplayName("结果未到齐：不抢锁、不触发收敛")
    void incompleteDoesNotTriggerConverge() throws IOException {
        String planId = "plan-2";
        when(valueOps.increment(AgentWorkflowOrchestrator.keyReceived(planId))).thenReturn(1L);
        when(valueOps.get(AgentWorkflowOrchestrator.keyTotal(planId))).thenReturn("3");
        when(valueOps.get(AgentWorkflowOrchestrator.keyMeta(planId)))
                .thenReturn("{\"reqId\":\"req-2\",\"userId\":100}");

        Channel channel = mock(Channel.class);
        collector.onResult(result(planId, "t0"), channel, 5L);

        // 未到齐：不抢收敛锁
        verify(valueOps, never()).setIfAbsent(eq(AgentWorkflowOrchestrator.keyLock(planId)),
                anyString(), any());
        verify(channel).basicAck(5L, false);
    }

    @Test
    @DisplayName("total 缺失（回滚后孤立回传结果）：total=0 不触发收敛")
    void orphanResultAfterRollbackDoesNotConverge() throws IOException {
        String planId = "plan-3";
        when(valueOps.increment(AgentWorkflowOrchestrator.keyReceived(planId))).thenReturn(1L);
        // total key 已删除（回滚后），get 返回 null
        when(valueOps.get(AgentWorkflowOrchestrator.keyTotal(planId))).thenReturn(null);

        Channel channel = mock(Channel.class);
        collector.onResult(result(planId, "t0"), channel, 5L);

        // total=0 → complete=false，绝不触发收敛锁
        verify(valueOps, never()).setIfAbsent(eq(AgentWorkflowOrchestrator.keyLock(planId)),
                anyString(), any());
        verify(channel).basicAck(5L, false);
    }

    @Test
    @DisplayName("处理异常：nack requeue（结果丢失会导致收敛永远等不到）")
    void failureNackRequeue() throws IOException {
        String planId = "plan-4";
        when(valueOps.increment(AgentWorkflowOrchestrator.keyReceived(planId)))
                .thenThrow(new RuntimeException("Redis 异常"));

        Channel channel = mock(Channel.class);
        collector.onResult(result(planId, "t0"), channel, 5L);

        verify(channel).basicNack(5L, false, true);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
