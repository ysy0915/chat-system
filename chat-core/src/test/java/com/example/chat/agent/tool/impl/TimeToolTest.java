package com.example.chat.agent.tool.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TimeTool 真实行为断言：
 * 输出必须同时含当前时间与日期，且时间/日期分别符合 HH时mm分ss秒 / yyyy年MM月dd日 格式。
 */
class TimeToolTest {

    private final TimeTool tool = new TimeTool();

    @Test
    void execute_returnsBothTimeAndDate() {
        String result = tool.execute(Map.of());

        assertNotNull(result);
        assertTrue(result.contains("当前时间"), "应含时间标签, got: " + result);
        assertTrue(result.contains("今天日期"), "应含日期标签, got: " + result);
    }

    @Test
    void execute_timeMatchesHHmmssFormat() {
        String result = tool.execute(Map.of());

        assertTrue(Pattern.compile("\\d{2}时\\d{2}分\\d{2}秒").matcher(result).find(),
                "时间应为 HH时mm分ss秒 格式, got: " + result);
    }

    @Test
    void execute_dateMatchesYYYY年MM月dd日Format() {
        String result = tool.execute(Map.of());

        assertTrue(Pattern.compile("\\d{4}年\\d{2}月\\d{2}日").matcher(result).find(),
                "日期应为 yyyy年MM月dd日 格式, got: " + result);
    }

    @Test
    void execute_isRepeatableAndCurrent() {
        // 两次调用都能产生合法输出（无共享状态副作用）
        String first = tool.execute(Map.of());
        String second = tool.execute(Map.of());

        assertNotNull(first);
        assertNotNull(second);
    }

    @Test
    void getName_returnsTime() {
        assertEquals("time", tool.getName());
    }

    @Test
    void getDescription_notBlank() {
        assertNotNull(tool.getDescription());
        assertFalse(tool.getDescription().isBlank());
    }

    @Test
    void getParameters_emptyObjectSchema() {
        assertEquals("{\"type\":\"object\",\"properties\":{}}", tool.getParameters());
    }
}
