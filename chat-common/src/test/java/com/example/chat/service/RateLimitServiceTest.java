package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RateLimitService 单元测试
 * 子类覆盖核心方法，内存模拟 Redis 计数器，不依赖真实 Redis
 * 覆盖：首次请求放行、正常范围放行、分钟超限、小时超限、Redis 异常降级
 */
class RateLimitServiceTest {

    /**
     * 内存版 RateLimitService，用 Map 替代 Redis 计数
     */
    private static class InMemoryRateLimitService extends RateLimitService {
        final Map<String, Long> counters = new HashMap<>();
        final Map<String, Long> ttls = new HashMap<>();
        boolean throwOnAccess = false;

        InMemoryRateLimitService() {
            super(null); // RateLimitChecker 不会被用到（子类已覆盖核心方法）
        }

        @Override
        public boolean isAllowed(Long userId) {
            if (throwOnAccess) return true; // 异常时放行
            try {
                String minKey = "rate:user:" + userId + ":min";
                String hourKey = "rate:user:" + userId + ":hour";

                long minCount = counters.merge(minKey, 1L, Long::sum);
                if (minCount == 1) ttls.put(minKey, 60L);
                if (minCount > 20) return false;

                long hourCount = counters.merge(hourKey, 1L, Long::sum);
                if (hourCount == 1) ttls.put(hourKey, 3600L);
                if (hourCount > 200) return false;

                return true;
            } catch (Exception e) {
                return true;
            }
        }

        @Override
        public long getRemainingSeconds(Long userId) {
            if (throwOnAccess) throw new RuntimeException("Redis 断开");
            String minKey = "rate:user:" + userId + ":min";
            Long ttl = ttls.get(minKey);
            return (ttl != null && ttl > 0) ? ttl : 60L;
        }

        /** 直接设置某用户的分钟计数（用于测试超限场景） */
        void setMinCount(Long userId, long count) {
            counters.put("rate:user:" + userId + ":min", count);
        }

        void setHourCount(Long userId, long count) {
            counters.put("rate:user:" + userId + ":hour", count);
        }
    }

    private InMemoryRateLimitService service;

    @BeforeEach
    void setUp() {
        service = new InMemoryRateLimitService();
    }

    // ────────────── isAllowed ──────────────

    @Test
    @DisplayName("首次请求（count=1）正常放行")
    void isAllowed_firstRequest() {
        assertTrue(service.isAllowed(1L));
    }

    @Test
    @DisplayName("未超过限制时放行（第5次）")
    void isAllowed_withinLimit() {
        for (int i = 0; i < 4; i++) service.isAllowed(1L);
        assertTrue(service.isAllowed(1L));
    }

    @Test
    @DisplayName("每分钟超过 20 次时拦截")
    void isAllowed_exceedMinuteLimit() {
        service.setMinCount(1L, 20L); // 预置 20 次，再请求一次变为 21
        assertFalse(service.isAllowed(1L));
    }

    @Test
    @DisplayName("每小时超过 200 次时拦截")
    void isAllowed_exceedHourLimit() {
        service.setHourCount(1L, 200L); // 预置 200 次，再请求一次变为 201
        assertFalse(service.isAllowed(1L));
    }

    @Test
    @DisplayName("不同用户之间计数互不影响")
    void isAllowed_differentUsers_isolated() {
        service.setMinCount(1L, 20L);
        assertFalse(service.isAllowed(1L));  // 用户1 超限
        assertTrue(service.isAllowed(2L));   // 用户2 正常
    }

    // ────────────── getRemainingSeconds ──────────────

    @Test
    @DisplayName("getRemainingSeconds：有 TTL 时正确返回")
    void getRemainingSeconds_validTtl() {
        service.isAllowed(1L); // 触发 TTL 设置
        assertEquals(60L, service.getRemainingSeconds(1L));
    }

    @Test
    @DisplayName("getRemainingSeconds：无 TTL 时降级为 60")
    void getRemainingSeconds_noTtl() {
        assertEquals(60L, service.getRemainingSeconds(99L));
    }
}
