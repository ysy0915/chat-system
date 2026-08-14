package com.example.chat.llm.rag.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <h2>Redis 对话记忆 KV 实现（MemoryKVStore SPI）</h2>
 *
 * <p>包装 {@link StringRedisTemplate} 提供短期记忆（List）与用户画像（String）原语，
 * 由 <code>app.rag.backend=milvus</code>（默认）开启。Redis 不可用时静默降级。</p>
 *
 * @see MemoryKVStore
 */
@Component
@ConditionalOnProperty(name = "app.rag.backend", havingValue = "milvus", matchIfMissing = true)
public class RedisMemoryKVStore implements MemoryKVStore {

    private static final Logger log = LoggerFactory.getLogger(RedisMemoryKVStore.class);

    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void pushRightAndTrim(String key, String value, int maxEntries, Duration ttl) {
        if (redisTemplate == null || key == null || value == null) {
            return;
        }
        try {
            redisTemplate.opsForList().rightPush(key, value);
            redisTemplate.opsForList().trim(key, -Math.max(1, maxEntries), -1);
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.expire(key, ttl.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.debug("[MemoryKV] pushRightAndTrim 失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public List<String> range(String key) {
        if (redisTemplate == null || key == null) {
            return List.of();
        }
        try {
            List<String> list = redisTemplate.opsForList().range(key, 0, -1);
            return list != null ? list : List.of();
        } catch (Exception e) {
            log.debug("[MemoryKV] range 失败 key={}: {}", key, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        if (redisTemplate == null || key == null || value == null) {
            return;
        }
        try {
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.opsForValue().set(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
            } else {
                redisTemplate.opsForValue().set(key, value);
            }
        } catch (Exception e) {
            log.debug("[MemoryKV] set 失败 key={}: {}", key, e.getMessage());
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
            log.debug("[MemoryKV] get 失败 key={}: {}", key, e.getMessage());
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
            log.debug("[MemoryKV] delete 失败 key={}: {}", key, e.getMessage());
        }
    }
}
