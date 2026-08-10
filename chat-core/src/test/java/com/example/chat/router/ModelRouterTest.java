package com.example.chat.router;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * ModelRouter 测试 — Mock ModelConfigRepository
 */
@ExtendWith(MockitoExtension.class)
class ModelRouterTest {

    @Mock
    private ModelConfigRepository modelConfigRepository;

    // ---- route(taskType, scene, preferredModelId) ----

    @Test
    void shouldRouteToHighestScoredModelForSummarization() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "deepseek", "deepseek-chat", 3, true),
            buildConfig(2L, "qwen", "qwen-plus", 1, true),
            buildConfig(3L, "qwen", "qwen-turbo", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.SUMMARIZATION, "chat", null);

        // SUMMARIZATION: qwen-turbo(100) > qwen-plus(90) > deepseek(80)
        assertEquals("qwen-turbo", decision.selectedModel);
        assertEquals("qwen", decision.selectedProvider);
        assertNotNull(decision.reason);
    }

    @Test
    void shouldRouteToHighestScoredModelForCode() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "qwen", "qwen-plus", 1, true),
            buildConfig(2L, "deepseek", "deepseek-coder", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.CODE, "chat", null);

        // CODE: deepseek(100) > qwen-plus(80)
        assertEquals("deepseek", decision.selectedProvider);
    }

    @Test
    void shouldRouteToHighestScoredModelForEmotional() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "qwen", "qwen-plus", 1, true),
            buildConfig(2L, "doubao", "doubao-seed-character", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.EMOTIONAL, "treehole", null);

        // EMOTIONAL: character(100) > qwen-plus(90)
        assertEquals("doubao-seed-character", decision.selectedModel);
    }

    @Test
    void shouldRouteToHighestScoredModelForSimpleChat() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "deepseek", "deepseek-chat", 2, true),
            buildConfig(2L, "qwen", "qwen-plus", 1, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.SIMPLE_CHAT, "chat", null);

        // SIMPLE_CHAT: qwen-plus(100) > deepseek(80)
        assertEquals("qwen-plus", decision.selectedModel);
    }

    @Test
    void shouldUsePreferredModelIdWhenMatching() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "deepseek", "deepseek-chat", 1, true),
            buildConfig(2L, "qwen", "qwen-plus", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.COMPLEX_REASONING, "chat", 1L);

        assertEquals("deepseek-chat", decision.selectedModel);
        assertTrue(decision.reason.contains("用户指定"));
    }

    @Test
    void shouldFallbackToDefaultWhenNoModels() {
        when(modelConfigRepository.findAllEnabled()).thenReturn(Collections.emptyList());

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.COMPLEX_REASONING, "chat", null);

        assertEquals("无可用模型", decision.reason);
        assertNull(decision.selectedConfig);
    }

    @Test
    void shouldRecordAlternatives() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "qwen", "qwen-turbo", 1, true),
            buildConfig(2L, "qwen", "qwen-plus", 2, true),
            buildConfig(3L, "deepseek", "deepseek-chat", 3, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.SUMMARIZATION, "chat", null);

        assertEquals("qwen-turbo", decision.selectedModel);
        assertTrue(decision.alternatives.size() >= 2,
                "应有至少 2 个备选模型，实际: " + decision.alternatives.size());
    }

    // ---- 视觉任务 ----

    @Test
    void shouldExcludeNonVisionModelsForVisionTask() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "deepseek", "deepseek-chat", 1, true),
            buildConfig(2L, "qwen", "qwen-vl-plus", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        RoutingDecision decision = router.route(TaskType.VISION, "chat", null);

        assertEquals("qwen-vl-plus", decision.selectedModel,
                "VISION 任务不应选择 deepseek-chat（无视觉能力）");
    }

    // ---- toJson() ----

    @Test
    void shouldProduceJsonWithoutThrowing() {
        List<ModelConfig> models = Collections.singletonList(
            buildConfig(1L, "qwen", "qwen-plus", 1, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        String json = router.route(TaskType.COMPLEX_REASONING, "chat", null).toJson();

        assertNotNull(json);
        assertTrue(json.contains("taskType"));
    }

    // ---- priority 排序 ----

    @Test
    void shouldPrioritizeByPriorityAscending() {
        List<ModelConfig> models = Arrays.asList(
            buildConfig(1L, "qwen", "qwen-turbo", 3, true),
            buildConfig(2L, "deepseek", "deepseek-chat", 1, true),
            buildConfig(3L, "doubao", "doubao-1.5pro", 2, true)
        );
        when(modelConfigRepository.findAllEnabled()).thenReturn(models);

        ModelRouter router = new ModelRouter(modelConfigRepository);
        // COMPLEX_REASONING: deepseek(90), qwen-turbo(80), doubao(80)
        // priority 只是排序依据，最终按 score 决定
        RoutingDecision decision = router.route(TaskType.COMPLEX_REASONING, "chat", null);

        assertEquals("deepseek-chat", decision.selectedModel);
    }

    private ModelConfig buildConfig(Long id, String provider, String model, int priority, boolean enabled) {
        ModelConfig config = new ModelConfig();
        config.id = id;
        config.provider = provider;
        config.model = model;
        config.priority = priority;
        config.enabled = enabled;
        return config;
    }
}
