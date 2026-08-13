package com.example.chat.agent.tool.impl;

import com.example.chat.client.RagClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * KnowledgeSearchTool 真实行为断言：
 * 缺参/空白参数、空结果、有结果格式化、自定义 kb_id、非法 kb_id 回退、下游异常降级。
 * 全部通过 mock RagClient 实现，不依赖外部服务。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeSearchToolTest {

    @Mock
    private RagClient ragClient;

    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeSearchTool(ragClient);
        // 直接 new 不走 Spring 容器，手动注入配置默认值（契约：kbId=1 / topK=5）
        ReflectionTestUtils.setField(tool, "defaultKbId", 1L);
        ReflectionTestUtils.setField(tool, "topK", 5);
    }

    @Test
    void execute_missingQuery_returnsMissingParam() {
        // Act
        String result = tool.execute(Map.of());

        // Assert
        assertEquals("[缺少参数: query]", result);
        verifyNoInteractions(ragClient);
    }

    @Test
    void execute_blankQuery_returnsMissingParam() {
        String result = tool.execute(Map.of("query", "   "));

        assertEquals("[缺少参数: query]", result);
        verifyNoInteractions(ragClient);
    }

    @Test
    void execute_noResults_returnsNotFoundWithDefaultKbAndTopK() {
        // Arrange
        when(ragClient.search(1L, "什么是 AI", 5)).thenReturn(List.of());

        // Act
        String result = tool.execute(Map.of("query", "什么是 AI"));

        // Assert
        assertEquals("[未检索到相关内容，query=什么是 AI]", result);
        verify(ragClient).search(1L, "什么是 AI", 5);
    }

    @Test
    void execute_withResults_formatsCountScoreSourceAndText() {
        // Arrange
        when(ragClient.search(1L, "什么是 AI", 5))
                .thenReturn(List.of(
                        new RagClient.SearchResult("AI 是人工智能的缩写", "doc1.pdf", 10L, 0.95f),
                        new RagClient.SearchResult("机器学习基础", "doc2.txt", 11L, 0.82f)));

        // Act
        String result = tool.execute(Map.of("query", "什么是 AI"));

        // Assert
        assertTrue(result.contains("检索到 2 条相关内容"), "应含命中数量, got: " + result);
        assertTrue(result.contains("相似度: 0.950"), "相似度应保留 3 位小数, got: " + result);
        assertTrue(result.contains("来源: doc1.pdf"), "应含来源, got: " + result);
        assertTrue(result.contains("AI 是人工智能的缩写"), "应含分片文本, got: " + result);
        assertTrue(result.contains("机器学习基础"), "应含第二分片文本, got: " + result);
        verify(ragClient).search(1L, "什么是 AI", 5);
    }

    @Test
    void execute_customKbId_passedThrough() {
        // Arrange
        when(ragClient.search(3L, "天气", 5)).thenReturn(List.of());

        // Act
        String result = tool.execute(Map.of("query", "天气", "kb_id", "3"));

        // Assert
        assertEquals("[未检索到相关内容，query=天气]", result);
        verify(ragClient).search(3L, "天气", 5);
    }

    @Test
    void execute_invalidKbId_fallsBackToDefault() {
        // Arrange
        when(ragClient.search(1L, "天气", 5)).thenReturn(List.of());

        // Act
        String result = tool.execute(Map.of("query", "天气", "kb_id", "abc"));

        // Assert
        assertEquals("[未检索到相关内容，query=天气]", result);
        verify(ragClient).search(1L, "天气", 5);
    }

    @Test
    void execute_clientException_returnsFailureMessage() {
        // Arrange
        when(ragClient.search(anyLong(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("connection refused"));

        // Act
        String result = tool.execute(Map.of("query", "什么是 AI"));

        // Assert
        assertTrue(result.startsWith("[知识库检索失败:"), "应返回降级信息, got: " + result);
        assertTrue(result.contains("connection refused"), "应透传异常原因, got: " + result);
    }

    @Test
    void getName_returnsKnowledgeSearch() {
        assertEquals("knowledge_search", tool.getName());
    }

    @Test
    void getDescription_notBlank() {
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isBlank());
    }

    @Test
    void getParameters_declaresQueryRequired() {
        String params = tool.getParameters();
        assertTrue(params.contains("\"query\""), "参数应含 query, got: " + params);
        assertTrue(params.contains("\"required\":[\"query\"]"), "query 应为必填, got: " + params);
    }
}
