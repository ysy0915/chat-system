package com.example.chat.storage;

import java.time.Duration;

/**
 * <h2>KV 存储 SPI 接口（Redis / Etcd / Memcached… 热插拔）</h2>
 *
 * <p>[B档] 存储平台化抽象：通用 String KV 语义（set/get/delete/exists + TTL）。
 * 现有 {@code chat-llm} 的 {@code RedisKeyValueStore}（Redis）实现本接口，
 * 经 {@link StorageRegistry} 注册为 type={@code kv}。</p>
 *
 * @see Storage
 */
public interface KeyValueStore extends Storage {

    /** 存储大类类型标识 */
    @Override
    default String type() {
        return "kv";
    }

    /**
     * 写入键值（可选 TTL，null 表示永不过期）。
     */
    void set(String key, String value, Duration ttl);

    /**
     * 读取键值；不存在返回 null。
     */
    String get(String key);

    /**
     * 删除键；不存在时静默成功。
     */
    void delete(String key);

    /**
     * 键是否存在。
     */
    boolean exists(String key);
}
