package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLMCallRecorder 每日调用统计聚合逻辑测试（首次初始化 / 累加 / 失败计数 / 30 天过期 / 异常吞没）。
 */
@ExtendWith(MockitoExtension.class)
class LLMCallRecorderTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private LLMCallRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        recorder = new LLMCallRecorder();
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        java.lang.reflect.Field field = LLMCallRecorder.class.getDeclaredField("redisTemplate");
        field.setAccessible(true);
        field.set(recorder, redisTemplate);
    }

    @Test
    @DisplayName("首次记录：初始化 total=1 success=1 并设置 30 天过期")
    void record_firstCall_initializesStats() {
        when(hashOperations.get(startsWith("llm:stats:"), eq("deepseek"))).thenReturn(null);

        recorder.record("deepseek", "deepseek-chat", "chat", true, 100, 50);

        verify(redisTemplate).expire(startsWith("llm:stats:"), eq(Duration.ofDays(30)));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(startsWith("llm:stats:"), eq("deepseek"), json.capture());
        assertTrue(json.getValue().contains("\"total\":1"), json.getValue());
        assertTrue(json.getValue().contains("\"success\":1"), json.getValue());
        assertTrue(json.getValue().contains("\"fail\":0"), json.getValue());
        assertTrue(json.getValue().contains("\"totalLatency\":100"), json.getValue());
        assertTrue(json.getValue().contains("\"totalAnswerLen\":50"), json.getValue());
    }

    @Test
    @DisplayName("成功记录：在既有统计上累加 total/latency/answerLen")
    void record_existingStats_accumulates() {
        when(hashOperations.get(startsWith("llm:stats:"), eq("qwen")))
                .thenReturn("{\"total\":1,\"success\":1,\"fail\":0,\"totalLatency\":100,\"totalAnswerLen\":50}");

        recorder.record("qwen", "qwen-max", "chat", true, 50, 30);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(startsWith("llm:stats:"), eq("qwen"), json.capture());
        assertTrue(json.getValue().contains("\"total\":2"), json.getValue());
        assertTrue(json.getValue().contains("\"success\":2"), json.getValue());
        assertTrue(json.getValue().contains("\"totalLatency\":150"), json.getValue());
        assertTrue(json.getValue().contains("\"totalAnswerLen\":80"), json.getValue());
    }

    @Test
    @DisplayName("失败记录：fail 递增且 success 不变")
    void record_failedCall_incrementsFail() {
        when(hashOperations.get(startsWith("llm:stats:"), eq("doubao")))
                .thenReturn("{\"total\":1,\"success\":1,\"fail\":0,\"totalLatency\":100,\"totalAnswerLen\":50}");

        recorder.record("doubao", "doubao-pro", "chat", false, 200, 0);

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(startsWith("llm:stats:"), eq("doubao"), json.capture());
        assertTrue(json.getValue().contains("\"total\":2"), json.getValue());
        assertTrue(json.getValue().contains("\"success\":1"), json.getValue());
        assertTrue(json.getValue().contains("\"fail\":1"), json.getValue());
        assertTrue(json.getValue().contains("\"totalLatency\":300"), json.getValue());
    }

    @Test
    @DisplayName("Redis 异常被吞没不抛出")
    void record_redisDown_doesNotThrow() {
        when(hashOperations.get(any(), any())).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> recorder.record("deepseek", "deepseek-chat", "chat", true, 1, 1));
    }
}
