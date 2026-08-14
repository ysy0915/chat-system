package com.example.chat.llm.rag.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>纯内存对话记忆 KV 实现（MemoryKVStore SPI）</h2>
 *
 * <p>standalone 模式（<code>app.rag.backend=memory</code>）下替代 Redis，
 * 用 <code>ConcurrentHashMap</code> 保存短期记忆列表与用户画像，TTL 采用惰性过期
 * （读取时校验时间戳）。数据仅存活于进程内、重启即失。</p>
 *
 * @see MemoryKVStore
 */
@Component
@ConditionalOnProperty(name = "app.rag.backend", havingValue = "memory")
public class InMemoryMemoryKVStore implements MemoryKVStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryKVStore.class);

    /** key → 短期记忆列表（List 元素为序列化 JSON） */
    private final Map<String, List<String>> lists = new ConcurrentHashMap<>();

    /** key → 字符串值 + 过期时间戳 */
    private final Map<String, Val> values = new ConcurrentHashMap<>();

    /** key → 列表过期时间戳（0 表示不过期） */
    private final Map<String, Long> listExpiry = new ConcurrentHashMap<>();

    public InMemoryMemoryKVStore() {
        log.info("[MemoryKV] 纯内存对话记忆已启用（进程内存储，重启即失）");
    }

    @Override
    public void pushRightAndTrim(String key, String value, int maxEntries, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        synchronized (lists) {
            List<String> list = lists.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(value);
            int keep = Math.max(1, maxEntries);
            while (list.size() > keep) {
                list.remove(0);
            }
        }
        listExpiry.put(key, ttl != null && !ttl.isZero() && !ttl.isNegative()
                ? System.currentTimeMillis() + ttl.toMillis() : 0L);
    }

    @Override
    public List<String> range(String key) {
        if (key == null) {
            return List.of();
        }
        if (isExpired(listExpiry.get(key))) {
            lists.remove(key);
            listExpiry.remove(key);
            return List.of();
        }
        List<String> list = lists.get(key);
        return list != null ? List.copyOf(list) : List.of();
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (key == null || value == null) {
            return;
        }
        long expireAt = ttl != null && !ttl.isZero() && !ttl.isNegative()
                ? System.currentTimeMillis() + ttl.toMillis() : 0L;
        values.put(key, new Val(value, expireAt));
    }

    @Override
    public String get(String key) {
        if (key == null) {
            return null;
        }
        Val val = values.get(key);
        if (val == null) {
            return null;
        }
        if (isExpired(val.expireAt)) {
            values.remove(key);
            return null;
        }
        return val.value;
    }

    @Override
    public void delete(String key) {
        if (key == null) {
            return;
        }
        lists.remove(key);
        listExpiry.remove(key);
        values.remove(key);
    }

    private boolean isExpired(Long expireAt) {
        return expireAt != null && expireAt > 0 && expireAt < System.currentTimeMillis();
    }

    private static final class Val {
        final String value;
        final long expireAt;

        Val(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }
}
