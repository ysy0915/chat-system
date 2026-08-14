package com.example.chat.llm.rag.legacy;

import java.time.Duration;
import java.util.List;

/**
 * <h2>对话记忆 KV 存储抽象</h2>
 *
 * <p>覆盖对话记忆（短期记忆 + 用户画像）所需的 KV/List 原语，
 * 由 Redis（{@link RedisMemoryKVStore}）或纯内存（{@link InMemoryMemoryKVStore}）实现。
 * standalone 模式（无 Redis）时自动切换到内存实现，短期记忆与画像功能照常可用。</p>
 *
 * <ul>
 *   <li>{@link #pushRightAndTrim} — 追加到列表尾部并裁剪到最近 N 条（短期记忆）</li>
 *   <li>{@link #set} / {@link #get} — 字符串 KV 带 TTL（用户画像）</li>
 *   <li>{@link #range} / {@link #delete} — 读取列表 / 删除键</li>
 * </ul>
 */
public interface MemoryKVStore {

    /**
     * 追加 value 到 key 尾部，并只保留最近 {@code maxEntries} 条（Redis 语义的 RLPUSH + LTRIM）。
     *
     * @param key        键（memory:{scene}:{userId}）
     * @param value      序列化后的单轮对话 JSON
     * @param maxEntries 保留的最大条数
     * @param ttl        过期时间（null 表示不过期）
     */
    void pushRightAndTrim(String key, String value, int maxEntries, Duration ttl);

    /**
     * 读取列表全部元素（不存在返回空列表）。
     */
    List<String> range(String key);

    /**
     * 写入字符串值并设置 TTL（null 表示不过期）。
     */
    void set(String key, String value, Duration ttl);

    /**
     * 读取字符串值；不存在返回 null。
     */
    String get(String key);

    /**
     * 删除键（不存在静默成功）。
     */
    void delete(String key);
}
