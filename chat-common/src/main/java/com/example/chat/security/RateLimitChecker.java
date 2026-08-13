package com.example.chat.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 统一 Redis 固定窗口限流检查器。
 *
 * 消除 RateLimitService 与 IpRateLimitInterceptor 中重复的
 * "increment + 首次设置 TTL + 超限判断 + Redis 异常 fail-open" 逻辑。
 *
 * 用法：
 * <pre>
 * boolean ok = rateLimitChecker.checkAndIncrement("rate:user:" + userId + ":min", 20, Duration.ofMinutes(1));
 * </pre>
 */
@Component
public class RateLimitChecker {
    private static final Logger log = LoggerFactory.getLogger(RateLimitChecker.class);

    private final StringRedisTemplate redis;

    public RateLimitChecker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 固定窗口计数并判断是否超限。
     * 首次计数时设置窗口过期时间；Redis 异常时放行（fail-open）。
     *
     * @param key   Redis 计数 key
     * @param limit 窗口内允许的最大次数
     * @param ttl   窗口时长
     * @return true 允许；false 已超限
     */
    public boolean checkAndIncrement(String key, int limit, Duration ttl) {
        try {
            Long current = redis.opsForValue().increment(key);
            if (current != null && current == 1) {
                redis.expire(key, ttl);
            }
            return current == null || current <= limit;
        } catch (Exception e) {
            log.warn("[RATE_LIMIT] Redis 异常，放行 key={}: {}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 查询窗口剩余秒数（用于 429 响应的 retry_after 提示）。
     *
     * @param key     计数 key
     * @param fallback Redis 异常或 key 不存在时的默认值
     * @return 剩余秒数
     */
    public long getRemainingSeconds(String key, long fallback) {
        try {
            Long ttl = redis.getExpire(key);
            return ttl != null && ttl > 0 ? ttl : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 读取当前计数（key 不存在或 Redis 异常返回 0），用于"先查后增"的计数场景（如登录失败锁定）。
     */
    public long getCount(String key) {
        try {
            String v = redis.opsForValue().get(key);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("[RATE_LIMIT] Redis 读取失败 key={}: {}", key, e.getMessage());
            return 0;
        }
    }

    /**
     * 清空计数（用于成功场景重置，如登录成功后清零失败计数），Redis 异常时忽略。
     */
    public void reset(String key) {
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("[RATE_LIMIT] Redis 删除失败 key={}: {}", key, e.getMessage());
        }
    }
}
