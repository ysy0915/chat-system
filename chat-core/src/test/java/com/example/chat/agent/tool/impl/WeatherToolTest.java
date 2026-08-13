package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WeatherTool 真实行为断言：
 * 缺参/空白参数必须立即返回错误且不发外部请求；元数据（名称/描述/参数 Schema）行为校验。
 * 注：真实天气查询走 wttr.in 外网且 HttpClient 不可注入，单元测试仅覆盖确定性路径。
 */
class WeatherToolTest {

    private final WeatherTool tool = new WeatherTool();

    @Test
    void execute_missingCity_returnsMissingParam() {
        String result = tool.execute(Map.of());

        assertEquals("[缺少参数: city]", result);
    }

    @Test
    void execute_blankCity_returnsMissingParam() {
        String result = tool.execute(Map.of("city", "   "));

        assertEquals("[缺少参数: city]", result);
    }

    @Test
    void getName_returnsWeather() {
        assertEquals("weather", tool.getName());
    }

    @Test
    void getDescription_notBlank() {
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isBlank());
    }

    @Test
    void getParameters_declaresCityRequired() {
        String params = tool.getParameters();
        assertTrue(params.contains("\"city\""), "参数应含 city, got: " + params);
        assertTrue(params.contains("\"required\":[\"city\"]"), "city 应为必填, got: " + params);
    }
}
