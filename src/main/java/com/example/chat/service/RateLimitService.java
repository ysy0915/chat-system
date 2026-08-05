package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private final RedisTemplate<String, String> redisTemplate;

    private static final int PER_MINUTE_LIMIT = 20;
    private static final int PER_HOUR_LIMIT = 200;

    public RateLimitService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean isAllowed(Long userId) {
        try {
            String minuteKey = "rate:user:" + userId + ":min";
            String hourKey = "rate:user:" + userId + ":hour";

            Long minuteCount = redisTemplate.opsForValue().increment(minuteKey);
            if (minuteCount != null && minuteCount == 1) {
                redisTemplate.expire(minuteKey, Duration.ofMinutes(1));
            }
            if (minuteCount != null && minuteCount > PER_MINUTE_LIMIT) {
                log.warn("[RATE_LIMIT] userId={} minuteCount={} BLOCKED", userId, minuteCount);
                return false;
            }

            Long hourCount = redisTemplate.opsForValue().increment(hourKey);
            if (hourCount != null && hourCount == 1) {
                redisTemplate.expire(hourKey, Duration.ofHours(1));
            }
            if (hourCount != null && hourCount > PER_HOUR_LIMIT) {
                log.warn("[RATE_LIMIT] userId={} hourCount={} BLOCKED", userId, hourCount);
                return false;
            }

            return true;
        } catch (Exception ex) {
            log.warn("[WARN] RateLimit Redis error, allowing request: {}", ex.getMessage());
            return true;
        }
    }

    public long getRemainingSeconds(Long userId) {
        try {
            String minuteKey = "rate:user:" + userId + ":min";
            Long ttl = redisTemplate.getExpire(minuteKey);
            return ttl != null && ttl > 0 ? ttl : 60;
        } catch (Exception ex) {
            return 60;
        }
    }
}
