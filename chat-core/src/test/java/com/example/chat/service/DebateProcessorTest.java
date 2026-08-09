package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DebateProcessor 单元测试
 * 聚焦纯函数：模型解析、辩论prompt构建、整合prompt构建
 * 使用真实 DebateProcessor 实例（构造传 null），通过反射调用 private 方法
 */
class DebateProcessorTest {

    /**
     * 轻量实例：私有纯函数不依赖任何注入字段，pass null 即可
     */
    private final DebateProcessor processor = new DebateProcessor(null, null, null, null, null, null);

    // ────────── resolveDebateModels ──────────

    @Test
    @DisplayName("resolveDebateModels：三个模型都存在时返回map")
    void resolveDebateModels_allPresent() throws Exception {
        List<ModelConfig> configs = List.of(
                config("doubao", "doubao-pro"),
                config("qwen", "qwen-max"),
                config("deepseek", "deepseek-chat"));

        Map<Long, ModelConfig> result = invokeResolve(configs);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("doubao", result.get(1L).provider);
        assertEquals("qwen", result.get(2L).provider);
        assertEquals("deepseek", result.get(3L).provider);
    }

    @Test
    @DisplayName("resolveDebateModels：缺少豆包时返回null")
    void resolveDebateModels_missingDoubao() throws Exception {
        List<ModelConfig> configs = List.of(
                config("qwen", "qwen-max"),
                config("deepseek", "deepseek-chat"));

        assertNull(invokeResolve(configs));
    }

    @Test
    @DisplayName("resolveDebateModels：只有千问时返回null")
    void resolveDebateModels_onlyQwen() throws Exception {
        assertNull(invokeResolve(List.of(config("qwen", "qwen-max"))));
    }

    @Test
    @DisplayName("resolveDebateModels：空列表返回null")
    void resolveDebateModels_emptyList() throws Exception {
        assertNull(invokeResolve(List.of()));
    }

    // ────────── buildDebatePrompt ──────────

    @Test
    @DisplayName("buildDebatePrompt：第1轮包含独立分析指令")
    void buildDebatePrompt_round1() throws Exception {
        String prompt = invokeBuildPrompt("AI有意识吗？", List.of(), 1, "千问");

        assertTrue(prompt.contains("千问"));
        assertTrue(prompt.contains("AI有意识吗？"));
        assertTrue(prompt.contains("第 1 轮"));
        assertTrue(prompt.contains("独立见解"));
        assertFalse(prompt.contains("之前的讨论记录"));
    }

    @Test
    @DisplayName("buildDebatePrompt：第2轮包含历史讨论和反驳指令")
    void buildDebatePrompt_round2() throws Exception {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        List<Map<String, String>> round1 = new ArrayList<>();
        round1.add(Map.of("provider", "豆包", "answer", "AI是代码的产物"));
        round1.add(Map.of("provider", "千问", "answer", "AI是算法与数据的结合"));
        allRounds.add(round1);

        String prompt = invokeBuildPrompt("AI有意识吗？", allRounds, 2, "DeepSeek");

        assertTrue(prompt.contains("DeepSeek"));
        assertTrue(prompt.contains("第 2 轮"));
        assertTrue(prompt.contains("之前的讨论记录"));
        assertTrue(prompt.contains("豆包"));
        assertTrue(prompt.contains("AI是代码的产物"));
    }

    @Test
    @DisplayName("buildDebatePrompt：第3轮包含两轮历史")
    void buildDebatePrompt_round3() throws Exception {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        allRounds.add(List.of(Map.of("provider", "A", "answer", "观点A")));
        allRounds.add(List.of(Map.of("provider", "B", "answer", "观点B")));

        String prompt = invokeBuildPrompt("test?", allRounds, 3, "豆包");

        assertTrue(prompt.contains("第 3 轮"));
        assertTrue(prompt.contains("第 1 轮讨论"));
        assertTrue(prompt.contains("第 2 轮讨论"));
    }

    // ────────── buildSynthesisPrompt ──────────

    @Test
    @DisplayName("buildSynthesisPrompt：包含全部轮次和问题")
    void buildSynthesisPrompt_coversAllRounds() throws Exception {
        List<List<Map<String, String>>> allRounds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            allRounds.add(List.of(Map.of("provider", "Model" + i, "answer", "Answer" + i)));
        }

        String prompt = invokeBuildSynthesis("AI的未来？", allRounds, "千问");

        assertTrue(prompt.contains("千问"));
        assertTrue(prompt.contains("最终总结者"));
        assertTrue(prompt.contains("AI的未来？"));
        assertTrue(prompt.contains("第 1 轮"));
        assertTrue(prompt.contains("第 2 轮"));
        assertTrue(prompt.contains("第 3 轮"));
        assertTrue(prompt.contains("整合结论"));
    }

    @Test
    @DisplayName("buildSynthesisPrompt：空轮次仍包含基础结构")
    void buildSynthesisPrompt_empty() throws Exception {
        String prompt = invokeBuildSynthesis("test", List.of(), "总结者");

        assertTrue(prompt.contains("总结者"));
        assertTrue(prompt.contains("3轮辩论记录"));
        assertTrue(prompt.contains("test"));
    }

    // ────────── helpers ──────────

    private static ModelConfig config(String provider, String model) {
        ModelConfig c = new ModelConfig();
        c.provider = provider;
        c.model = model;
        c.enabled = true;
        return c;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ModelConfig> invokeResolve(List<ModelConfig> configs) throws Exception {
        java.lang.reflect.Method method = DebateProcessor.class.getDeclaredMethod(
                "resolveDebateModels", List.class);
        method.setAccessible(true);
        return (Map<Long, ModelConfig>) method.invoke(processor, configs);
    }

    @SuppressWarnings("unchecked")
    private String invokeBuildPrompt(String question, List<List<Map<String, String>>> allRounds,
                                      int round, String name) throws Exception {
        java.lang.reflect.Method method = DebateProcessor.class.getDeclaredMethod(
                "buildDebatePrompt", String.class, List.class, int.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(processor, question, allRounds, round, name);
    }

    @SuppressWarnings("unchecked")
    private String invokeBuildSynthesis(String question,
                                         List<List<Map<String, String>>> allRounds,
                                         String name) throws Exception {
        java.lang.reflect.Method method = DebateProcessor.class.getDeclaredMethod(
                "buildSynthesisPrompt", String.class, List.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(processor, question, allRounds, name);
    }
}
