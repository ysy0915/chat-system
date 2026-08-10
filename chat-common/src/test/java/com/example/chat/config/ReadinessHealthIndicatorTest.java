package com.example.chat.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReadinessHealthIndicator 单元测试
 * 覆盖：UP（Redis 正常）、DOWN（RedisTemplate 为 null）、DOWN（读写不一致）、DOWN（Redis 异常）
 */
@ExtendWith(MockitoExtension.class)
class ReadinessHealthIndicatorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ReadinessHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new ReadinessHealthIndicator();
        // 注入 mock RedisTemplate
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "redisTemplate", redisTemplate);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Redis 正常时返回 UP")
    void health_redisAvailable_returnsUp() {
        long now = System.currentTimeMillis();
        when(valueOperations.get(anyString())).thenReturn(String.valueOf(now));

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("connected", health.getDetails().get("redis"));
        assertEquals(true, health.getDetails().get("stateless"));
    }

    @Test
    @DisplayName("RedisTemplate 为 null 时返回 DOWN")
    void health_redisTemplateNull_returnsDown() {
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "redisTemplate", null);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().get("reason").toString().contains("未注入"));
    }

    @Test
    @DisplayName("Redis 读写不一致时返回 DOWN")
    void health_readWriteMismatch_returnsDown() {
        when(valueOperations.get(anyString())).thenReturn("old-value-12345");

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().get("reason").toString().contains("读写不一致"));
        verify(valueOperations).set(eq("health:readiness"), anyString(), eq(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("Redis 操作抛异常时返回 DOWN")
    void health_redisException_returnsDown() {
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("连接超时"));

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().get("reason").toString().contains("Redis 不可用"));
        assertTrue(health.getDetails().get("reason").toString().contains("连接超时"));
    }

    @Test
    @DisplayName("多次调用 health() 结果一致（幂等）")
    void health_isIdempotent() {
        when(valueOperations.get(anyString())).thenReturn("12345");

        Health h1 = indicator.health();
        Health h2 = indicator.health();

        assertEquals(h1.getStatus(), h2.getStatus());
        assertEquals(h1.getDetails(), h2.getDetails());
    }
}
