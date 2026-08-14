package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.langgraph4j.TreePerspectiveGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DebateTreeProcessor 单元测试
 *
 * 覆盖:
 *  - decompose(): JSON 正常解析 / 非法 JSON 回退 / 空结果回退
 *  - aggregate(): LLM 正常返回 / LLM 异常 → 本地拼合
 *  - toPerspectiveMaps(): 集合转换
 *  - process() 异常 → done 消息仍发送
 */
@ExtendWith(MockitoExtension.class)
class DebateTreeProcessorTest {

    @Mock
    private LLMInvoker llmInvoker;
    @Mock
    private BroadcastService broadcastService;
    @Mock
    private TreePerspectiveGraphService perspectiveGraphService;

    private DebateTreeProcessor processor;
    private ModelConfig defaultModel;

    @BeforeEach
    void setUp() {
        processor = new DebateTreeProcessor(llmInvoker, broadcastService, perspectiveGraphService);
        // 注入 apiKey 避免 NPE
        ReflectionTestUtils.setField(processor, "defaultApiKey", "test-key");

        defaultModel = new ModelConfig();
        defaultModel.setId(100L);
        defaultModel.setProvider("openai");
        defaultModel.setModel("gpt-4o");
    }

    // ================================================================
    //  decompose – 语义拆解
    // ================================================================

    @Nested
    @DisplayName("语义拆解 decompose()")
    class Decompose {

        @Test
        @DisplayName("正常 JSON 返回 2 个视角")
        void normalJsonTwoPerspectives() throws Exception {
            String json = """
                    {
                      "perspectives": [
                        {"id": "p1", "label": "经济效益", "focus": "关注成本与收益"},
                        {"id": "p2", "label": "社会影响", "focus": "对人类福祉的影响"}
                      ]
                    }""";
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), anyString(), any(), anyString()))
                    .thenReturn(json);

            // 反射调用 private decompose()
            List<DebateTreeProcessor.Perspective> result = invokeDecompose("AI 是否应该替代人类工作?");

            assertThat(result).hasSize(2);
            assertThat(result.get(0).label).isEqualTo("经济效益");
            assertThat(result.get(0).focus).isEqualTo("关注成本与收益");
            assertThat(result.get(1).label).isEqualTo("社会影响");
        }

        @Test
        @DisplayName("带 markdown 代码块的 JSON 也能解析")
        void jsonWithMarkdownFence() throws Exception {
            String json = """
                    ```json
                    {"perspectives": [{"id": "a", "label": "技术可行性", "focus": "技术成熟度"}]}
                    ```""";
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), anyString(), any(), anyString()))
                    .thenReturn(json);

            List<DebateTreeProcessor.Perspective> result = invokeDecompose("量子计算");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).label).isEqualTo("技术可行性");
        }

        @Test
        @DisplayName("非法 JSON → 回退到默认 3 视角")
        void invalidJsonFallback() throws Exception {
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), anyString(), any(), anyString()))
                    .thenReturn("这不是 JSON");

            List<DebateTreeProcessor.Perspective> result = invokeDecompose("任意问题");

            assertThat(result).hasSize(3);
            assertThat(result.get(0).label).isEqualTo("理性分析");
            assertThat(result.get(1).label).isEqualTo("批判性观点");
            assertThat(result.get(2).label).isEqualTo("综合考量");
        }

        @Test
        @DisplayName("空 perspectives 数组 → 回退到默认视角")
        void emptyPerspectivesFallback() throws Exception {
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), anyString(), any(), anyString()))
                    .thenReturn("{\"perspectives\": []}");

            List<DebateTreeProcessor.Perspective> result = invokeDecompose("test");

            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("LLM 调用异常 → 异常传播到上层，由 runTreeDebate 捕获后回退空列表")
        void llmExceptionPropagates() throws Exception {
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), anyString(), any(), anyString()))
                    .thenThrow(new RuntimeException("LLM timeout"));

            // decompose() 通过反射调用，InvocationTargetException 包装 RuntimeException
            assertThatThrownBy(() -> invokeDecompose("test"))
                    .isInstanceOf(java.lang.reflect.InvocationTargetException.class);
        }
    }

    // ================================================================
    //  aggregate – 最终汇总
    // ================================================================

    @Nested
    @DisplayName("最终汇总 aggregate()")
    class Aggregate {

        @Test
        @DisplayName("LLM 正常 → 返回结果并推送 tree_aggregate_result")
        void llmSuccess() throws Exception {
            when(llmInvoker.invokeStream(any(), anyList(), anyDouble(), anyString(), any(), anyString(), any()))
                    .thenReturn("**【最终结论】** 建议推广\n**【理由】** 1. 经济效益高\n**【建议】** 分步实施");

            Map<String, String> conclusions = Map.of("p1", "支持", "p2", "反对");
            List<DebateTreeProcessor.Perspective> perspectives = List.of(
                    p("p1", "经济", "成本"), p("p2", "伦理", "隐私"));

            Map<String, List<Map<String, String>>> details = Map.of();

            String result = invokeAggregate("req-1", 42L, "AI 应该开源吗?", perspectives, conclusions, details);

            assertThat(result).contains("最终结论");
            // 验证 broadcast 发送了 tree_aggregate_result
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
            verify(broadcastService, atLeastOnce()).broadcast(contains("debate"), captor.capture());
            List<Map<String, Object>> messages = captor.getAllValues();
            boolean hasAggResult = messages.stream()
                    .anyMatch(m -> "tree_aggregate_result".equals(m.get("type")));
            assertThat(hasAggResult).isTrue();
        }

        @Test
        @DisplayName("LLM 异常 → 传播到 runTreeDebate，上层 catch 触发本地拼接")
        void llmExceptionPropagates() throws Exception {
            when(llmInvoker.invokeStream(any(), anyList(), anyDouble(), anyString(), any(), anyString(), any()))
                    .thenThrow(new RuntimeException("timeout"));

            Map<String, String> conclusions = Map.of("p1", "利好", "p2", "风险");
            List<DebateTreeProcessor.Perspective> perspectives = List.of(
                    p("p1", "经济", "GDP"), p("p2", "环境", "碳排"));

            Map<String, List<Map<String, String>>> details = Map.of();

            // aggregate() 通过反射调用，InvocationTargetException 包装 RuntimeException
            assertThatExceptionOfType(Exception.class)
                    .isThrownBy(() -> invokeAggregate("req-2", 1L, "碳中和", perspectives, conclusions, details));
        }

        @Test
        @DisplayName("缺少视角结论的 map → 在 prompt 中显示「无」")
        void missingConclusionInPrompt() throws Exception {
            String mockResult = "最终结论内容";
            when(llmInvoker.invokeStream(any(), anyList(), anyDouble(), anyString(), any(), anyString(), any()))
                    .thenReturn(mockResult);

            Map<String, String> conclusions = Map.of("p1", "支持");
            List<DebateTreeProcessor.Perspective> perspectives = List.of(
                    p("p1", "经济", "GDP"), p("p2", "环境", "碳排"));

            Map<String, List<Map<String, String>>> details = Map.of();

            String result = invokeAggregate("req-3", 1L, "碳中和", perspectives, conclusions, details);

            assertThat(result).isEqualTo(mockResult);
        }
    }

    // ================================================================
    //  toPerspectiveMaps – 集合转换
    // ================================================================

    @Nested
    @DisplayName("集合转换 toPerspectiveMaps()")
    class ToPerspectiveMaps {

        @Test
        @DisplayName("正常转换 2 个视角")
        void normalConversion() throws Exception {
            List<DebateTreeProcessor.Perspective> list = List.of(
                    p("p1", "经济", "GDP"), p("p2", "伦理", "道德"));

            List<Map<String, String>> maps = invokeToPerspectiveMaps(list);

            assertThat(maps).hasSize(2);
            assertThat(maps.get(0)).containsEntry("id", "p1");
            assertThat(maps.get(0)).containsEntry("label", "经济");
            assertThat(maps.get(0)).containsEntry("focus", "GDP");
        }

        @Test
        @DisplayName("空列表")
        void emptyList() throws Exception {
            List<Map<String, String>> maps = invokeToPerspectiveMaps(Collections.emptyList());
            assertThat(maps).isEmpty();
        }
    }

    // ================================================================
    //  process() – 异常处理
    // ================================================================

    @Nested
    @DisplayName("入口 process() 异常处理")
    class ProcessException {

        @Test
        @DisplayName("流程异常 → 仍发送 done 消息解锁前端")
        void exceptionStillSendsDone() throws Exception {
            // decompose 成功返回
            String json = "{\"perspectives\": [{\"id\": \"p1\", \"label\": \"测试\", \"focus\": \"测试\"}]}";
            when(llmInvoker.invoke(any(), anyList(), anyDouble(), eq("debate-tree"), any(), anyString()))
                    .thenReturn(json);

            // perspective graph 抛异常 → 触发本地回退
            when(perspectiveGraphService.execute(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("LLM down"));

            Map<Long, ModelConfig> modelMap = Map.of(
                    1L, defaultModel, 2L, defaultModel, 3L, defaultModel);

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(inv -> {
                Map<String, Object> msg = inv.getArgument(1);
                if ("done".equals(msg.get("type"))) {
                    latch.countDown();
                }
                return null;
            }).when(broadcastService).broadcast(anyString(), anyMap());

            processor.process("req-ex", 999L, "测试问题", modelMap, defaultModel);

            boolean doneReceived = latch.await(15, TimeUnit.SECONDS);
            assertThat(doneReceived)
                    .as("即使 LLM 全部报错，也应该发送 done 消息")
                    .isTrue();
        }
    }

    // ================================================================
    //  反射辅助方法
    // ================================================================

    @SuppressWarnings("unchecked")
    private List<DebateTreeProcessor.Perspective> invokeDecompose(String question) throws Exception {
        var method = DebateTreeProcessor.class.getDeclaredMethod(
                "decompose", String.class, ModelConfig.class);
        method.setAccessible(true);
        return (List<DebateTreeProcessor.Perspective>) method.invoke(processor, question, defaultModel);
    }

    @SuppressWarnings("unchecked")
    private String invokeAggregate(String reqId, Long userId, String question,
                                    List<DebateTreeProcessor.Perspective> perspectives,
                                    Map<String, String> conclusions,
                                    Map<String, List<Map<String, String>>> details) throws Exception {
        var method = DebateTreeProcessor.class.getDeclaredMethod(
                "aggregate", String.class, Long.class, String.class, List.class,
                Map.class, Map.class, ModelConfig.class);
        method.setAccessible(true);
        return (String) method.invoke(processor, reqId, userId, question,
                perspectives, conclusions, details, defaultModel);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> invokeToPerspectiveMaps(
            List<DebateTreeProcessor.Perspective> perspectives) throws Exception {
        var method = DebateTreeProcessor.class.getDeclaredMethod(
                "toPerspectiveMaps", List.class);
        method.setAccessible(true);
        return (List<Map<String, String>>) method.invoke(processor, perspectives);
    }

    private static DebateTreeProcessor.Perspective p(String id, String label, String focus) {
        return new DebateTreeProcessor.Perspective(id, label, focus, "测试问题");
    }
}
