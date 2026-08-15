package com.example.chat.llm.rag.legacy;

import com.example.chat.dto.LangChainResponse;
import com.example.chat.llm.service.LLMInvokeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * FactExtractor 单元测试：
 * LLM 事实抽取 JSON 解析、非法输出/失败降级、长度过滤与去重、round 截断。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FactExtractor 事实抽取")
class FactExtractorTest {

    @Mock
    private LLMInvokeService llmInvokeService;

    private FactExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new FactExtractor(llmInvokeService, new ObjectMapper());
        ReflectionTestUtils.setField(extractor, "maxFactsPerRound", 5);
        ReflectionTestUtils.setField(extractor, "factProvider", "qwen");
        ReflectionTestUtils.setField(extractor, "factModel", "qwen-plus");
    }

    @Test
    @DisplayName("LLM 返回合法 JSON 数组时提取事实")
    void extract_okJson_extracts() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":[\"用户是程序员\",\"用户喜欢咖啡\"]}", "qwen", "qwen-plus"));

        List<String> facts = extractor.extractFacts("我是程序员，喜欢喝咖啡", "很好");

        assertEquals(2, facts.size());
        assertTrue(facts.contains("用户是程序员"));
        assertTrue(facts.contains("用户喜欢咖啡"));
    }

    @Test
    @DisplayName("LLM 返回无 facts 字段或非法 JSON 时返回空")
    void extract_invalidJson_returnsEmpty() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "抱歉无法抽取", "qwen", "qwen-plus"));

        assertTrue(extractor.extractFacts("随便说点", "回应").isEmpty());
    }

    @Test
    @DisplayName("LLM 调用失败时返回空列表")
    void extract_llmFail_returnsEmpty() {
        when(llmInvokeService.invoke(any())).thenReturn(
                LangChainResponse.fail("timeout", "qwen"));

        assertTrue(extractor.extractFacts("q", "a").isEmpty());
    }

    @Test
    @DisplayName("facts 为 null 或空数组时返回空")
    void extract_nullOrEmptyFacts_returnsEmpty() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":[]}", "qwen", "qwen-plus"));
        assertTrue(extractor.extractFacts("q", "a").isEmpty());

        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":null}", "qwen", "qwen-plus"));
        assertTrue(extractor.extractFacts("q", "a").isEmpty());
    }

    @Test
    @DisplayName("超长或过短事实被过滤")
    void extract_lengthFilter() {
        StringBuilder longFact = new StringBuilder("用户喜欢");
        while (longFact.length() <= 80) {
            longFact.append("非常非常长的内容");
        }
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":[\"短\",\"" + longFact + "\",\"用户正常事实\"]}", "qwen", "qwen-plus"));

        List<String> facts = extractor.extractFacts("q", "a");

        assertEquals(1, facts.size());
        assertEquals("用户正常事实", facts.get(0));
    }

    @Test
    @DisplayName("重复事实去重")
    void extract_deduplicates() {
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":[\"用户喜欢咖啡\",\"用户喜欢咖啡\",\"用户喜欢茶\"]}", "qwen", "qwen-plus"));

        List<String> facts = extractor.extractFacts("q", "a");

        assertEquals(2, facts.size());
    }

    @Test
    @DisplayName("超出 maxFactsPerRound 时截断")
    void extract_truncatesPerRound() {
        ReflectionTestUtils.setField(extractor, "maxFactsPerRound", 2);
        when(llmInvokeService.invoke(any())).thenReturn(LangChainResponse.ok(
                "{\"facts\":[\"事实一\",\"事实二\",\"事实三\"]}", "qwen", "qwen-plus"));

        List<String> facts = extractor.extractFacts("q", "a");

        assertEquals(2, facts.size());
    }
}
