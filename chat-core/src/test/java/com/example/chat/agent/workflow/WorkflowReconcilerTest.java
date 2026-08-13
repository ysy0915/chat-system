package com.example.chat.agent.workflow;

import com.example.chat.agent.planner.AgentWorkflowOrchestrator;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Set;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * WorkflowReconciler 扫描索引（ZSet）聚焦测试。
 *
 * <p>验证方案 A 的两条候选收集路径：</p>
 * <ul>
 *   <li>ZSet 非空 → 走 {@code ZRANGEBYSCORE 0 now} 索引扫描（O(logN)），
 *       绝不触发全量 keys()；</li>
 *   <li>ZSet 为空（升级前存量 plan）→ 兜底 legacyScan() 全量 keys()，
 *       保证存量任务仍可被对账。</li>
 * </ul>
 */
class WorkflowReconcilerTest {

    private static final String ZSET_KEY = "agent:reconciler:plans";

    private StringRedisTemplate redisTemplate;
    @SuppressWarnings("unchecked")
    private ZSetOperations<String, String> zsetOps;
    private WorkflowReconciler reconciler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        zsetOps = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        reconciler = new WorkflowReconciler(redisTemplate, mock(MessageRepository.class),
                mock(AgentWorkflowOrchestrator.class), new ObjectMapper());
    }

    @Test
    @DisplayName("ZSet 非空时走索引扫描：rangeByScore 命中到期候选，不触发 keys()")
    void zSetFirstWhenIndexNonEmpty() {
        when(zsetOps.zCard(ZSET_KEY)).thenReturn(2L);
        when(zsetOps.rangeByScore(eq(ZSET_KEY), anyDouble(), anyDouble(), anyLong(), anyLong()))
                .thenReturn(Set.of("plan-1", "plan-2"));

        reconciler.reconcile();

        // 索引路径被使用：只按 score 取到期候选（min=0, max=now）
        verify(zsetOps).rangeByScore(eq(ZSET_KEY), eq(0.0), anyDouble(), eq(0L), eq(500L));
        // 绝无全量扫描
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    @DisplayName("ZSet 为空（存量 plan）时兜底 legacyScan 全量 keys()")
    void legacyFallbackWhenZSetEmpty() {
        when(zsetOps.zCard(ZSET_KEY)).thenReturn(0L);
        when(redisTemplate.keys("agent:plan:*:meta")).thenReturn(Set.of("agent:plan:old-1:meta"));

        reconciler.reconcile();

        // 索引为空 → 兜底全量扫描，保证升级前已启动的存量 plan 仍可被对账
        verify(redisTemplate).keys("agent:plan:*:meta");
        // 无需走索引读取
        verify(zsetOps, never()).rangeByScore(anyString(), anyDouble(), anyDouble(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("ZSet 为 null（Redis 异常）时兜底 legacyScan 不崩溃")
    void legacyFallbackWhenZCardNull() {
        when(zsetOps.zCard(ZSET_KEY)).thenReturn(null);
        when(redisTemplate.keys("agent:plan:*:meta")).thenReturn(null);

        reconciler.reconcile(); // 不应抛异常

        verify(redisTemplate).keys("agent:plan:*:meta");
    }
}
