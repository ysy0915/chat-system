package com.example.chat.llm.rag.legacy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * InMemoryMemoryKVStore 单元测试：
 * 列表 trim、字符串 KV、TTL 惰性过期、delete 与 null 短路。
 */
@DisplayName("InMemoryMemoryKVStore 纯内存记忆 KV")
class InMemoryMemoryKVStoreTest {

    private InMemoryMemoryKVStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryKVStore();
    }

    @Test
    @DisplayName("列表保留最近 maxEntries 条")
    void pushRightAndTrim_keepsRecent() {
        store.pushRightAndTrim("k", "a", 3, null);
        store.pushRightAndTrim("k", "b", 3, null);
        store.pushRightAndTrim("k", "c", 3, null);
        store.pushRightAndTrim("k", "d", 3, null);

        assertEquals(List.of("b", "c", "d"), store.range("k"));
    }

    @Test
    @DisplayName("maxEntries <= 0 时保留至少 1 条")
    void pushRightAndTrim_maxEntriesNonPositive() {
        store.pushRightAndTrim("k", "a", 0, null);
        store.pushRightAndTrim("k", "b", 0, null);

        assertEquals(List.of("b"), store.range("k"));
    }

    @Test
    @DisplayName("不存在的 key range 返回空")
    void range_missing_returnsEmpty() {
        assertEquals(List.of(), store.range("nope"));
    }

    @Test
    @DisplayName("set/get 正常读写，TTL 过期后惰性清除")
    void setGet_withTtlExpiry() throws InterruptedException {
        store.set("k", "v", Duration.ofMillis(30));
        assertEquals("v", store.get("k"));

        Thread.sleep(60);
        assertNull(store.get("k"));
    }

    @Test
    @DisplayName("无 TTL 永不过期")
    void setGet_withoutTtl_neverExpires() {
        store.set("k", "v", null);
        store.set("k2", "v2", Duration.ZERO);
        assertEquals("v", store.get("k"));
        assertEquals("v2", store.get("k2"));
    }

    @Test
    @DisplayName("delete 清除列表与值")
    void delete_clears() {
        store.pushRightAndTrim("k", "a", 5, null);
        store.set("v", "x", null);

        store.delete("k");
        store.delete("v");

        assertEquals(List.of(), store.range("k"));
        assertNull(store.get("v"));
    }

    @Test
    @DisplayName("null key/value 短路不抛")
    void nullKeyValue_skips() {
        store.pushRightAndTrim(null, "v", 5, null);
        store.pushRightAndTrim("k", null, 5, null);
        store.set(null, "v", null);
        store.set("k", null, null);
        assertEquals(List.of(), store.range(null));
        assertNull(store.get(null));
        store.delete(null);
    }
}
