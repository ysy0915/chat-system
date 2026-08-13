package com.example.chat.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TraceRecorder 真实行为断言：
 * record 写 Redis List（leftPush + trim 保留 retention + 30 天过期）、
 * getRecentTraces 解析 JSON 行、searchTraces 关键字过滤、异常降级空列表。
 */
@ExtendWith(MockitoExtension.class)
class TraceRecorderTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    private TraceRecorder recorder;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "stringRedisTemplate", stringRedisTemplate);
        ReflectionTestUtils.setField(recorder, "retention", 1000);
    }

    private CallTrace sampleTrace(String traceId) {
        return new CallTrace(traceId, "chat", "qwen", "qwen-turbo",
                1L, 2L, 1L, "SUCCESS", null, null);
    }

    @Test
    void record_writesPushTrimAndExpire() {
        // Act
        recorder.record(sampleTrace("abc12345"));

        // Assert
        verify(listOps).leftPush(startsWith("trace:"), contains("\"traceId\":\"abc12345\""));
        verify(listOps).leftPush(startsWith("trace:"), contains("\"status\":\"SUCCESS\""));
        verify(listOps).trim(startsWith("trace:"), eq(0L), eq(999L));
        verify(stringRedisTemplate).expire(startsWith("trace:"), eq(Duration.ofDays(30)));
    }

    @Test
    void getRecentTraces_parsesJsonLines() {
        // Arrange
        String json = "{\"traceId\":\"aaaa\",\"scene\":\"chat\",\"provider\":\"qwen\","
                + "\"model\":\"qwen-turbo\",\"startTime\":1,\"endTime\":2,\"latency\":1,"
                + "\"status\":\"SUCCESS\",\"errorMessage\":\"\",\"toolCalls\":\"\"}";
        when(listOps.range(startsWith("trace:"), eq(0L), eq(9L))).thenReturn(List.of(json));

        // Act
        List<Map<String, Object>> traces = recorder.getRecentTraces(10);

        // Assert
        assertEquals(1, traces.size());
        assertEquals("aaaa", traces.get(0).get("traceId"));
        assertEquals("SUCCESS", traces.get(0).get("status"));
        assertEquals("qwen", traces.get(0).get("provider"));
    }

    @Test
    void getRecentTraces_zeroOrNegative_usesDefaultWindow() {
        // n<=0 → 默认 20 条窗口 range(key, 0, 19)
        when(listOps.range(startsWith("trace:"), eq(0L), eq(19L))).thenReturn(List.of());

        List<Map<String, Object>> traces = recorder.getRecentTraces(0);

        assertTrue(traces.isEmpty());
    }

    @Test
    void getRecentTraces_redisError_returnsEmptyList() {
        // Arrange
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new RuntimeException("redis down"));

        // Act
        List<Map<String, Object>> traces = recorder.getRecentTraces(10);

        // Assert
        assertTrue(traces.isEmpty());
    }

    @Test
    void searchTraces_filtersByKeyword() {
        // Arrange
        String hit = "{\"traceId\":\"abc123\",\"scene\":\"chat\",\"provider\":\"qwen\","
                + "\"model\":\"qwen-turbo\",\"startTime\":1,\"endTime\":2,\"latency\":1,"
                + "\"status\":\"FAIL\",\"errorMessage\":\"\",\"toolCalls\":\"\"}";
        String miss = "{\"traceId\":\"zzzz\",\"scene\":\"chat\",\"provider\":\"qwen\","
                + "\"model\":\"qwen-turbo\",\"startTime\":1,\"endTime\":2,\"latency\":1,"
                + "\"status\":\"SUCCESS\",\"errorMessage\":\"\",\"toolCalls\":\"\"}";
        when(listOps.range(startsWith("trace:"), eq(0L), eq(-1L))).thenReturn(List.of(hit, miss));

        // Act
        List<Map<String, Object>> found = recorder.searchTraces("abc");

        // Assert
        assertEquals(1, found.size());
        assertEquals("abc123", found.get(0).get("traceId"));
    }

    @Test
    void searchTraces_blankKeyword_fallsBackToRecentFifty() {
        // 空关键字 → getRecentTraces(50) → range(key, 0, 49)
        when(listOps.range(startsWith("trace:"), eq(0L), eq(49L))).thenReturn(List.of());

        List<Map<String, Object>> found = recorder.searchTraces("   ");

        assertTrue(found.isEmpty());
    }
}
