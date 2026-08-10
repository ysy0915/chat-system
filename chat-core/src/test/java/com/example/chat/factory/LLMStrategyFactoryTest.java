package com.example.chat.factory;

import com.example.chat.router.TaskType;
import com.example.chat.strategy.DoubaoStrategy;
import com.example.chat.strategy.LLMStrategy;
import com.example.chat.strategy.OpenAICompatStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMStrategyFactory 纯逻辑测试 — 无需 Mock（策略用 mock ObjectMapper 构造）
 */
class LLMStrategyFactoryTest {

    private LLMStrategyFactory factory;
    private OpenAICompatStrategy openAICompat;
    private DoubaoStrategy doubao;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        openAICompat = new OpenAICompatStrategy(mapper);
        doubao = new DoubaoStrategy(mapper);
        factory = new LLMStrategyFactory(openAICompat, doubao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen", "doubao", "unknown_provider", "anthropic"})
    void shouldRouteToOpenAICompatForStandardProviders(String provider) {
        LLMStrategy strategy = factory.getStrategy(provider);
        assertSame(openAICompat, strategy, provider + " should use OpenAICompatStrategy");
    }

    @Test
    void shouldRouteDoubaoResponsesToDoubaoStrategy() {
        LLMStrategy strategy = factory.getStrategy("doubao_responses");
        assertSame(doubao, strategy);
    }

    @Test
    void shouldRouteNullProviderToOpenAICompat() {
        LLMStrategy strategy = factory.getStrategy(null);
        assertSame(openAICompat, strategy);
    }

    @Test
    void shouldRouteCaseInsensitively() {
        assertSame(openAICompat, factory.getStrategy("DEEPSEEK"));
        assertSame(doubao, factory.getStrategy("Doubao_Responses"));
    }

    // ---- getStrategyForTask ----

    @Test
    void visionTaskShouldForceOpenAICompatEvenForDoubaoResponses() {
        LLMStrategy strategy = factory.getStrategyForTask(TaskType.VISION, "doubao_responses");
        assertSame(openAICompat, strategy, "VISION 必须走 OpenAICompatStrategy");
    }

    @Test
    void nonVisionTaskShouldUseProviderRouteForComplexReasoning() {
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.COMPLEX_REASONING, "deepseek"));
        assertSame(doubao, factory.getStrategyForTask(TaskType.CREATIVE, "doubao_responses"));
    }

    @Test
    void getStrategyForTaskWithNullTaskTypeShouldUseProviderRoute() {
        LLMStrategy strategy = factory.getStrategyForTask(null, "doubao_responses");
        assertSame(doubao, strategy);
    }

    @Test
    void visionTaskShouldForceOpenAICompatForAnyProvider() {
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.VISION, "deepseek"));
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.VISION, "qwen"));
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.VISION, "doubao"));
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.VISION, null));
    }

    @Test
    void simpleChatTaskShouldUseProviderRoute() {
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.SIMPLE_CHAT, "deepseek"));
        assertSame(doubao, factory.getStrategyForTask(TaskType.SIMPLE_CHAT, "doubao_responses"));
    }

    @Test
    void codeTaskShouldUseProviderRoute() {
        assertSame(openAICompat, factory.getStrategyForTask(TaskType.CODE, "qwen"));
    }
}
