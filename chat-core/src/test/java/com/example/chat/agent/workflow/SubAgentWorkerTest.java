package com.example.chat.agent.workflow;

import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.tool.ToolRegistry;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.BaseUrlResolver;
import com.example.chat.util.LlmToolInvoker;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubAgentWorker 失败重试（DLX 死信 + 指数退避）聚焦测试。
 *
 * <p>覆盖三类核心逻辑：</p>
 * <ul>
 *   <li>{@link SubAgentWorker#deathCount}：x-death 头解析（null/空/单条/多条取最大）；</li>
 *   <li>{@link SubAgentWorker#backoffDelayMs}：1s→2s→4s→…指数退避且封顶 60s；</li>
 *   <li>{@link SubAgentWorker#onSubTask}：成功回传 + ack；可重试失败进 DLX 延迟重试 + ack（不再 nack）；</li>
 *   <li>达到最大执行次数后回传终态失败 + ack，不再重试。</li>
 * </ul>
 */
class SubAgentWorkerTest {

    private LLMInvoker llmInvoker;
    private LlmConfigProperties llmConfig;
    private ToolRegistry toolRegistry;
    private SubTaskProducer subTaskProducer;
    private SubAgentWorker worker;

    @BeforeEach
    void setUp() {
        llmInvoker = mock(LLMInvoker.class);
        llmConfig = mock(LlmConfigProperties.class);
        toolRegistry = mock(ToolRegistry.class);
        subTaskProducer = mock(SubTaskProducer.class);
        worker = new SubAgentWorker(llmInvoker, llmConfig, toolRegistry,
                mock(BaseUrlResolver.class), mock(LlmToolInvoker.class), subTaskProducer);
        // 默认重试配置：initial=1000ms max=60000ms max-attempts=5（与 application.yml 一致）
        ReflectionTestUtils.setField(worker, "retryInitialDelayMs", 1000L);
        ReflectionTestUtils.setField(worker, "retryMaxDelayMs", 60000L);
        ReflectionTestUtils.setField(worker, "retryMaxAttempts", 5);
    }

    private SubAgentTask task() {
        SubAgentTask t = new SubAgentTask();
        t.taskId = "t1";
        t.planId = "p1";
        t.instructions = "分析市场";
        return t;
    }

    // ═══════════ deathCount ═══════════

    @Test
    @DisplayName("deathCount: null / 空列表返回 0（首次执行）")
    void deathCountNullOrEmpty() {
        assertEquals(0, worker.deathCount(null));
        assertEquals(0, worker.deathCount(Collections.emptyList()));
    }

    @Test
    @DisplayName("deathCount: 单条 x-death 返回 count")
    void deathCountSingle() {
        List<Map<String, Object>> xDeath = List.of(Map.of("count", 2));
        assertEquals(2, worker.deathCount(xDeath));
    }

    @Test
    @DisplayName("deathCount: 多条 x-death（多跳队列）取最大 count")
    void deathCountMultipleTakesMax() {
        List<Map<String, Object>> xDeath = List.of(
                Map.of("count", 1),
                Map.of("count", 3),
                Map.of("count", 2)
        );
        assertEquals(3, worker.deathCount(xDeath));
    }

    // ═══════════ backoffDelayMs ═══════════

    @Test
    @DisplayName("backoffDelayMs: 指数退避 1s→2s→4s→8s")
    void backoffExponential() {
        assertEquals(1000L, worker.backoffDelayMs(0));
        assertEquals(2000L, worker.backoffDelayMs(1));
        assertEquals(4000L, worker.backoffDelayMs(2));
        assertEquals(8000L, worker.backoffDelayMs(3));
    }

    @Test
    @DisplayName("backoffDelayMs: 封顶 60s（第 7 次起不再增长）")
    void backoffCappedAtMax() {
        assertEquals(60000L, worker.backoffDelayMs(6));
        assertEquals(60000L, worker.backoffDelayMs(10));
        assertEquals(60000L, worker.backoffDelayMs(20));
    }

    // ═══════════ onSubTask ═══════════

    @Test
    @DisplayName("执行成功: 回传成功结果 + ack，不触发重试")
    void onSubTaskSuccess() throws Exception {
        when(llmConfig.getModel()).thenReturn("qwen-plus");
        when(toolRegistry.getToolsSchema(any())).thenReturn(Collections.emptyList());
        when(llmInvoker.invoke(any(), anyList(), anyDouble(), eq("subagent"), any(), any()))
                .thenReturn("市场摘要");

        Channel channel = mock(Channel.class);
        worker.onSubTask(task(), channel, 42L, null);

        verify(subTaskProducer).sendResult(argThat(r ->
                r.success && "市场摘要".equals(r.summary) && "t1".equals(r.taskId)));
        verify(subTaskProducer, never()).sendRetry(any(), anyLong());
        verify(channel).basicAck(42L, false);
    }

    @Test
    @DisplayName("首次失败: 进入 DLX 延迟重试（初始退避 1s）+ ack，不再 nack")
    void onSubTaskRetryableFirstFailure() throws Exception {
        when(llmConfig.getModel()).thenReturn("qwen-plus");
        when(toolRegistry.getToolsSchema(any())).thenReturn(Collections.emptyList());
        when(llmInvoker.invoke(any(), anyList(), anyDouble(), eq("subagent"), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        Channel channel = mock(Channel.class);
        // x-death 为空 = 首次执行（deathCount=0 → attempt=1 < 5 → 可重试）
        worker.onSubTask(task(), channel, 7L, null);

        verify(subTaskProducer).sendRetry(argThat(t -> "t1".equals(t.taskId)), eq(1000L));
        verify(subTaskProducer, never()).sendResult(any());
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("第 3 次失败: 指数退避 4s 后重试")
    void onSubTaskRetryBackoff() throws Exception {
        when(llmConfig.getModel()).thenReturn("qwen-plus");
        when(toolRegistry.getToolsSchema(any())).thenReturn(Collections.emptyList());
        when(llmInvoker.invoke(any(), anyList(), anyDouble(), eq("subagent"), any(), any()))
                .thenThrow(new RuntimeException("LLM 超时"));

        Channel channel = mock(Channel.class);
        List<Map<String, Object>> xDeath = List.of(Map.of("count", 2)); // 已重试 2 次 → 第 3 次
        worker.onSubTask(task(), channel, 7L, xDeath);

        verify(subTaskProducer).sendRetry(argThat(t -> "t1".equals(t.taskId)), eq(4000L));
        verify(channel).basicAck(7L, false);
    }

    @Test
    @DisplayName("达到最大执行次数: 回传终态失败 + ack，不再重试")
    void onSubTaskTerminalFailure() throws Exception {
        when(llmConfig.getModel()).thenReturn("qwen-plus");
        when(toolRegistry.getToolsSchema(any())).thenReturn(Collections.emptyList());
        when(llmInvoker.invoke(any(), anyList(), anyDouble(), eq("subagent"), any(), any()))
                .thenThrow(new RuntimeException("持续失败"));

        Channel channel = mock(Channel.class);
        // deathCount=4 → attempt=5 达到上限 → 终态失败
        List<Map<String, Object>> xDeath = List.of(Map.of("count", 4));
        worker.onSubTask(task(), channel, 7L, xDeath);

        verify(subTaskProducer).sendResult(argThat((SubAgentResult r) ->
                !r.success && "t1".equals(r.taskId) && r.error != null));
        verify(subTaskProducer, never()).sendRetry(any(), anyLong());
        verify(channel).basicAck(7L, false);
    }
}
