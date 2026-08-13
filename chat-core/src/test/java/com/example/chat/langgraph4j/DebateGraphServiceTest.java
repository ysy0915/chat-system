package com.example.chat.langgraph4j;

import com.example.chat.client.LlmBundleClient;
import com.example.chat.dto.GraphStreamEventDto;
import com.example.chat.dto.LangGraphRequest;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.BroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private DebateGraphService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DebateGraphService();
        inject("modelConfigRepository", modelConfigRepository);
        inject("llmBundleClient", llmBundleClient);
        inject("broadcastService", broadcastService);
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
        when(llmBundleClient.graphStream(any(), any())).thenReturn(true);

        service.execute("r2", 2L, "话题", 99);

        // maxSteps = effectiveRounds * 4 + 2，99 被钳制为 10 → 42
        ArgumentCaptor<LangGraphRequest> captor = ArgumentCaptor.forClass(LangGraphRequest.class);
        verify(llmBundleClient).graphStream(captor.capture(), any(Consumer.class));
        assertEquals(10 * 4 + 2, captor.getValue().getMaxSteps());
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
