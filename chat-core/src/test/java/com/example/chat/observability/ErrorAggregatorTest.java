package com.example.chat.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ErrorAggregator 真实行为断言：
 * recordError 写 Redis Hash（increment + 附属信息 + 30 天过期）、
 * getErrorStats 聚合解析、getTopErrors 按计数排序、异常降级空列表。
 */
@ExtendWith(MockitoExtension.class)
class ErrorAggregatorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private ErrorAggregator aggregator;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
        aggregator = new ErrorAggregator();
        ReflectionTestUtils.setField(aggregator, "stringRedisTemplate", stringRedisTemplate);
    }

    @Test
    void recordError_incrementsAndStoresMetadata() {
        // Act
        aggregator.recordError("chat", "qwen", "qwen-turbo", ErrorType.TIMEOUT, "request timed out");

        // Assert
        verify(hashOps).increment(startsWith("error:agg:"), eq("qwen:TIMEOUT"), eq(1L));
        verify(hashOps).put(startsWith("error:agg:"), eq("qwen:TIMEOUT:lastMsg"), eq("request timed out"));
        verify(hashOps).put(startsWith("error:agg:"), eq("qwen:TIMEOUT:lastModel"), eq("qwen-turbo"));
        verify(hashOps).put(startsWith("error:agg:"), eq("qwen:TIMEOUT:lastScene"), eq("chat"));
        verify(stringRedisTemplate).expire(startsWith("error:agg:"), eq(Duration.ofDays(30)));
    }

    @Test
    void getErrorStats_aggregatesCountAndMetadata() {
        // Arrange
        Map<Object, Object> raw = new HashMap<>();
        raw.put("qwen:TIMEOUT", 3L);
        raw.put("qwen:TIMEOUT:lastMsg", "request timed out");
        raw.put("qwen:TIMEOUT:lastModel", "qwen-turbo");
        raw.put("qwen:TIMEOUT:lastScene", "chat");
        when(hashOps.entries(startsWith("error:agg:"))).thenReturn(raw);

        // Act
        List<Map<String, Object>> stats = aggregator.getErrorStats();

        // Assert
        assertEquals(1, stats.size());
        Map<String, Object> m = stats.get(0);
        assertEquals("qwen", m.get("provider"));
        assertEquals("TIMEOUT", m.get("errorType"));
        assertEquals(3L, m.get("count"));
        assertEquals("request timed out", m.get("lastMessage"));
        assertEquals("qwen-turbo", m.get("lastModel"));
        assertEquals("chat", m.get("lastScene"));
    }

    @Test
    void getTopErrors_sortsByCountDescending() {
        // Arrange
        Map<Object, Object> raw = new HashMap<>();
        raw.put("qwen:TIMEOUT", 2L);
        raw.put("deepseek:RATE_LIMIT", 9L);
        when(hashOps.entries(anyString())).thenReturn(raw);

        // Act
        List<Map<String, Object>> top = aggregator.getTopErrors(1);

        // Assert
        assertEquals(1, top.size());
        assertEquals("deepseek", top.get(0).get("provider"));
        assertEquals("RATE_LIMIT", top.get(0).get("errorType"));
        assertEquals(9L, top.get(0).get("count"));
    }

    @Test
    void getErrorStats_redisError_returnsEmptyList() {
        // Arrange
        when(hashOps.entries(anyString())).thenThrow(new RuntimeException("redis down"));

        // Act
        List<Map<String, Object>> stats = aggregator.getErrorStats();

        // Assert
        assertTrue(stats.isEmpty());
    }

    @Test
    void getTopErrors_negativeN_usesDefaultTen() {
        // n<=0 → 默认 10 条；数据少于 10 返回全部
        Map<Object, Object> raw = new HashMap<>();
        raw.put("qwen:TIMEOUT", 5L);
        when(hashOps.entries(anyString())).thenReturn(raw);

        List<Map<String, Object>> top = aggregator.getTopErrors(0);

        assertEquals(1, top.size());
        assertEquals("qwen", top.get(0).get("provider"));
    }
}
