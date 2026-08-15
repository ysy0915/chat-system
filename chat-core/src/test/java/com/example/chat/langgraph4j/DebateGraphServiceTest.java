package com.example.chat.langgraph4j;

import com.example.chat.client.LlmBundleClient;
import com.example.chat.dto.GraphStreamEventDto;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.BroadcastService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DebateGraphService 辩论图编排测试（模型缺失校验 / 图数据结构 / 轮次钳制）。
 */
@ExtendWith(MockitoExtension.class)
class DebateGraphServiceTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    @Mock
    private LlmBundleClient llmBundleClient;

    @Mock
    private BroadcastService broadcastService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DebateGraphService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DebateGraphService();
        inject("modelConfigRepository", modelConfigRepository);
        inject("llmBundleClient", llmBundleClient);
        inject("broadcastService", broadcastService);
        inject("redisTemplate", redisTemplate);
        inject("objectMapper", objectMapper);
    }

    private void inject(String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = DebateGraphService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Test
    @DisplayName("缺少豆包模型时抛出明确异常")
    void execute_missingDoubaoModel_throws() {
        when(modelConfigRepository.findAllEnabledByType("chat"))
                .thenReturn(List.of(model("deepseek", "deepseek-chat")));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.execute("r1", 1L, "话题"));
        assertTrue(ex.getMessage().contains("豆包"), ex.getMessage());
    }

    @Test
    @DisplayName("图结构：5 节点 5 边、debate 三分支、summary 终结、shouldContinue 条件循环")
    void buildGraph_structureIsComplete() throws Exception {
        LangGraphRequest req = invokeBuildGraph("r1", 1L, "话题", 3);

        assertEquals("incrementRound", req.getEntryPoint());
        assertEquals(5, req.getNodes().size());
        assertEquals(5, req.getEdges().size());
        assertEquals(3 * 4 + 2, req.getMaxSteps());

        LangGraphRequest.GraphNode debate = req.getNodes().stream()
                .filter(n -> "debate".equals(n.getId())).findFirst().orElseThrow();
        assertEquals(3, debate.getBranches().size());
        assertTrue(debate.getBranches().get(0).isSinkAppend(), "正方分支应追加到 proArguments");

        LangGraphRequest.GraphNode summary = req.getNodes().stream()
                .filter(n -> "summary".equals(n.getId())).findFirst().orElseThrow();
        assertTrue(summary.isTerminal(), "summary 应为终结节点");

        LangGraphRequest.GraphEdge condEdge = req.getEdges().stream()
                .filter(e -> "shouldContinue".equals(e.getFrom()) && e.getCondition() != null)
                .findFirst().orElseThrow();
        assertTrue(condEdge.getCondition().contains("contains"), condEdge.getCondition());
        assertTrue(req.getEdges().stream().anyMatch(e ->
                "shouldContinue".equals(e.getFrom()) && e.isDefaultRoute()), "shouldContinue 应有默认路由到 summary");
    }

    @Test
    @DisplayName("轮次数经 execute 钳制在 1-10 之间")
    void execute_roundsClamped_buildsClampedGraph() {
        when(modelConfigRepository.findAllEnabledByType("chat"))
                .thenReturn(List.of(model("doubao", "doubao-pro"),
                        model("deepseek", "deepseek-chat"),
                        model("qwen", "qwen-max")));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(llmBundleClient.graphStream(any(), any())).thenReturn(true);

        service.execute("r2", 2L, "话题", 99);

        // maxSteps = effectiveRounds * 4 + 2，99 被钳制为 10 → 42
        ArgumentCaptor<LangGraphRequest> captor = ArgumentCaptor.forClass(LangGraphRequest.class);
        verify(llmBundleClient).graphStream(captor.capture(), any(Consumer.class));
        assertEquals(10 * 4 + 2, captor.getValue().getMaxSteps());
    }

    @Test
    @DisplayName("带历史记忆时：state 注入 historySummary，debate prompt 引用反思立场与历史记忆")
    void buildGraph_withHistoryMemory_injectsSummaryAndReflections() throws Exception {
        Method method = DebateGraphService.class.getDeclaredMethod(
                "buildGraph", String.class, Long.class, String.class,
                ModelConfig.class, ModelConfig.class, ModelConfig.class, int.class, String.class);
        method.setAccessible(true);
        LangGraphRequest req = (LangGraphRequest) method.invoke(service, "r3", 3L, "话题",
                model("doubao", "doubao-pro"),
                model("deepseek", "deepseek-chat"),
                model("qwen", "qwen-max"), 3, "正方上次立场：A；反方上次立场：B；");

        assertEquals("正方上次立场：A；反方上次立场：B；", req.getState().get("historySummary"));

        LangGraphRequest.GraphNode debate = req.getNodes().stream()
                .filter(n -> "debate".equals(n.getId())).findFirst().orElseThrow();
        String proPrompt = debate.getBranches().get(0).getUserPrompt();
        assertTrue(proPrompt.contains("{{state.conReflections[-1]}}"), "正方应引用反方反思后的立场");
        assertTrue(proPrompt.contains("{{state.historySummary}}"), "正方应引用历史辩论记忆");

        LangGraphRequest.GraphNode reflect = req.getNodes().stream()
                .filter(n -> "reflect".equals(n.getId())).findFirst().orElseThrow();
        assertTrue(reflect.getBranches().get(0).getUserPrompt().contains("{{state.conReflections[-1]}}"),
                "正方反思应参考反方上一轮反思立场");
    }

    @Test
    @DisplayName("execute 结束将反思立场写入 Redis 外存")
    void execute_savesHistoryMemoryToRedis() {
        when(modelConfigRepository.findAllEnabledByType("chat"))
                .thenReturn(List.of(model("doubao", "doubao-pro"),
                        model("deepseek", "deepseek-chat"),
                        model("qwen", "qwen-max")));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(llmBundleClient.graphStream(any(), any())).thenAnswer(inv -> {
            Consumer<GraphStreamEventDto> consumer = inv.getArgument(1);
            GraphStreamEventDto end = new GraphStreamEventDto(
                    GraphStreamEventDto.TYPE_BRANCH_END, "reflect", "pro", "反思A");
            consumer.accept(end);
            return true;
        });

        service.execute("r4", 4L, "话题", 3);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), valCaptor.capture(), any());
        assertTrue(keyCaptor.getValue().startsWith("debate:memory:4:"), keyCaptor.getValue());
        assertTrue(valCaptor.getValue().contains("反思A"), valCaptor.getValue());
        assertTrue(valCaptor.getValue().contains("\"rounds\":1"), valCaptor.getValue());
    }

    private LangGraphRequest invokeBuildGraph(String reqId, Long userId, String topic, int rounds)
            throws Exception {
        java.lang.reflect.Method method = DebateGraphService.class.getDeclaredMethod(
                "buildGraph", String.class, Long.class, String.class,
                ModelConfig.class, ModelConfig.class, ModelConfig.class, int.class);
        method.setAccessible(true);
        return (LangGraphRequest) method.invoke(service, reqId, userId, topic,
                model("doubao", "doubao-pro"),
                model("deepseek", "deepseek-chat"),
                model("qwen", "qwen-max"), rounds);
    }

    private static ModelConfig model(String provider, String modelName) {
        ModelConfig config = new ModelConfig();
        config.provider = provider;
        config.model = modelName;
        return config;
    }
}
