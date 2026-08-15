package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchTool 单元测试：
 * 参数校验（缺参/空白）、provider/apiKey/maxResults 配置注入、元数据。
 * 网络请求部分因 HttpClient 为 final 直接初始化且目标 URL 固定为外部站点，属集成测试范畴。
 */
@DisplayName("WebSearchTool 联网搜索工具")
class WebSearchToolTest {

    private WebSearchTool tool;

    @BeforeEach
    void setUp() {
        tool = new WebSearchTool();
        ReflectionTestUtils.setField(tool, "provider", "duckduckgo");
        ReflectionTestUtils.setField(tool, "apiKey", "");
        ReflectionTestUtils.setField(tool, "maxResults", 5);
        ReflectionTestUtils.setField(tool, "timeoutSeconds", 10);
    }

    @Test
    @DisplayName("元数据正确")
    void metadata() {
        assertEquals("web_search", tool.getName());
        assertNotNull(tool.getDescription());
        assertTrue(tool.getDescription().contains("联网搜索"));
        String params = tool.getParameters();
        assertTrue(params.contains("\"query\""));
        assertTrue(params.contains("\"required\":[\"query\"]"));
    }

    @Test
    @DisplayName("缺少 query 参数返回缺参提示")
    void execute_missingQuery_returnsMissingParam() {
        assertEquals("[缺少参数: query]", tool.execute(new HashMap<>()));
    }

    @Test
    @DisplayName("query 为空白返回缺参提示")
    void execute_blankQuery_returnsMissingParam() {
        assertEquals("[缺少参数: query]", tool.execute(Map.of("query", "  ")));
        assertEquals("[缺少参数: query]", tool.execute(Map.of("query", "")));
    }
}
