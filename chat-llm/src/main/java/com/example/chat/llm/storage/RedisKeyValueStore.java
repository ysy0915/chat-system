package com.example.chat.llm.storage;

import com.example.chat.storage.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * <h2>Redis KV 存储实现（KeyValueStore SPI）</h2>
 *
 * <p>[B档] 存储平台化：包装 {@link StringRedisTemplate} 提供通用 KV 语义，
 * 经 {@link StorageRegistry} 注册为 type={@code kv}。Redis 不可用时
 * 所有操作静默降级（返回 null / false / 空），不阻塞业务链路。</p>
 *
 * @see KeyValueStore
 */
@Component
public class RedisKeyValueStore implements KeyValueStore {

    private static final Logger log = LoggerFactory.getLogger(RedisKeyValueStore.class);

    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean isAvailable() {
        return redisTemplate != null;
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (redisTemplate == null || key == null) {
            return;
        }
        try {
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.opsForValue().set(key, value, ttl);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } catch (Exception e) {
            log.debug("[KVStore] set 失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public String get(String key) {
        if (redisTemplate == null || key == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.debug("[KVStore] get 失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void delete(String key) {
        if (redisTemplate == null || key == null) {
            return;
        }
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.debug("[KVStore] delete 失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public boolean exists(String key) {
        if (redisTemplate == null || key == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.debug("[KVStore] exists 失败 key={}: {}", key, e.getMessage());
            return false;
        }
    }
}
