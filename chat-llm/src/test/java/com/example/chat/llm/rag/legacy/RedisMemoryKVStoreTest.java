package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisMemoryKVStore 单元测试：
 * Redis 原语包装（rightPush/trim/expire、KV set/get/delete）、
 * Redis 未注入/异常时静默降级。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisMemoryKVStore Redis 记忆 KV")
class RedisMemoryKVStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisMemoryKVStore store;

    @BeforeEach
    void setUp() {
        store = new RedisMemoryKVStore();
        store.setRedisTemplate(redisTemplate);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForList()).thenReturn(listOps);
        org.mockito.Mockito.lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("pushRightAndTrim：rightPush + trim + 有效 TTL 才 expire")
    void pushRightAndTrim_pushTrimExpire() {
        store.pushRightAndTrim("k", "v", 3, Duration.ofHours(1));

        verify(listOps).rightPush("k", "v");
        verify(listOps).trim("k", -3, -1);
        verify(redisTemplate).expire(eq("k"), eq(3_600_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("maxEntries <= 0 时 trim 按 1 兜底")
    void pushRightAndTrim_maxEntriesNonPositive() {
        store.pushRightAndTrim("k", "v", 0, Duration.ofHours(1));
        store.pushRightAndTrim("k", "v", -5, Duration.ofHours(1));

        verify(listOps, org.mockito.Mockito.times(2)).trim("k", -1, -1);
    }

    @Test
    @DisplayName("ttl 为 null/零/负时不设置过期")
    void pushRightAndTrim_invalidTtl_noExpire() {
        store.pushRightAndTrim("k", "v", 3, null);
        store.pushRightAndTrim("k", "v", 3, Duration.ZERO);
        store.pushRightAndTrim("k", "v", 3, Duration.ofMillis(-1));

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("range 返回列表；Redis 返回 null 时转空列表")
    void range_normalOrNull() {
        when(listOps.range("k", 0, -1)).thenReturn(List.of("a", "b"));
        assertEquals(List.of("a", "b"), store.range("k"));

        when(listOps.range("k2", 0, -1)).thenReturn(null);
        assertEquals(List.of(), store.range("k2"));
    }

    @Test
    @DisplayName("set：有有效 TTL 带过期写入，否则普通写入")
    void set_withAndWithoutTtl() {
        store.set("k", "v", Duration.ofMinutes(30));
        verify(valueOps).set("k", "v", 1_800_000L, TimeUnit.MILLISECONDS);

        store.set("k", "v", null);
        verify(valueOps).set("k", "v");
    }

    @Test
    @DisplayName("get/delete 透传")
    void getDelete() {
        when(valueOps.get("k")).thenReturn("v");
        assertEquals("v", store.get("k"));
        assertEquals(null, store.get("missing"));

        store.delete("k");
        verify(redisTemplate).delete("k");
    }

    @Test
    @DisplayName("Redis 未注入时全部静默返回默认值")
    void noRedis_graceful() {
        RedisMemoryKVStore bare = new RedisMemoryKVStore();
        bare.pushRightAndTrim("k", "v", 3, Duration.ofHours(1));
        assertEquals(List.of(), bare.range("k"));
        bare.set("k", "v", Duration.ofHours(1));
        assertNull(bare.get("k"));
        bare.delete("k");
    }

    @Test
    @DisplayName("Redis 异常时静默降级")
    void redisError_graceful() {
        doThrow(new RuntimeException("redis down")).when(listOps).rightPush(anyString(), anyString());
        store.pushRightAndTrim("k", "v", 3, Duration.ofHours(1));

        doThrow(new RuntimeException("redis down")).when(listOps).range(anyString(), anyLong(), anyLong());
        assertEquals(List.of(), store.range("k"));

        doThrow(new RuntimeException("redis down")).when(valueOps).get(anyString());
        assertNull(store.get("k"));
    }

    @Test
    @DisplayName("null key/value 短路")
    void nullKeyValue_skips() {
        store.pushRightAndTrim(null, "v", 3, Duration.ofHours(1));
        store.pushRightAndTrim("k", null, 3, Duration.ofHours(1));
        store.set(null, "v", Duration.ofHours(1));
        store.set("k", null, Duration.ofHours(1));
        assertEquals(List.of(), store.range(null));
        assertNull(store.get(null));
        store.delete(null);

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }
}
