package com.example.chat.featureflag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FeatureFlagService 特性开关核心逻辑测试（灰度 / 白名单 / 黑名单 / 环境过滤 / 缓存）。
 */
@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    private static final String KEY_PREFIX = "feature:flag:";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private Environment environment;

    private FeatureFlagService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
        service = new FeatureFlagService(redisTemplate, new ObjectMapper(), environment);
    }

    // ---- 无配置默认关闭 ----

    @Test
    @DisplayName("Redis 无配置时默认关闭")
    void isEnabled_noConfig_returnsFalse() {
        when(valueOperations.get(anyString())).thenReturn(null);

        assertFalse(service.isEnabled("flag-x", "u1"));
        assertFalse(service.isEnabled("flag-x"));
    }

    // ---- enabled + 百分比 ----

    @Test
    @DisplayName("100% 灰度全量放行")
    void isEnabled_fullyRolledOut_returnsTrue() {
        when(valueOperations.get(KEY_PREFIX + "beta"))
                .thenReturn("{\"enabled\":true,\"percentage\":100}");

        assertTrue(service.isEnabled("beta", "u1"));
        assertTrue(service.isEnabled("beta", "u2"));
    }

    @Test
    @DisplayName("0% 灰度全部拒绝")
    void isEnabled_percentageZero_returnsFalse() {
        when(valueOperations.get(KEY_PREFIX + "g0"))
                .thenReturn("{\"enabled\":true,\"percentage\":0}");

        assertFalse(service.isEnabled("g0", "u1"));
    }

    @Test
    @DisplayName("同一用户在灰度区间内结果稳定")
    void isEnabled_grayscale_sameUserStable() {
        when(valueOperations.get(KEY_PREFIX + "g50"))
                .thenReturn("{\"enabled\":true,\"percentage\":50}");

        boolean first = service.isEnabled("g50", "user-42");
        boolean second = service.isEnabled("g50", "user-42");

        assertEquals(first, second);
    }

    // ---- 白名单 / 黑名单 ----

    @Test
    @DisplayName("白名单用户放行，其余按灰度拒绝")
    void isEnabled_whitelistedUser_returnsTrue() {
        when(valueOperations.get(KEY_PREFIX + "w"))
                .thenReturn("{\"enabled\":true,\"percentage\":0,\"whitelist\":[\"admin\"]}");

        assertTrue(service.isEnabled("w", "admin"));
        assertFalse(service.isEnabled("w", "normal"));
    }

    @Test
    @DisplayName("黑名单用户拒绝，其余放行")
    void isEnabled_blacklistedUser_returnsFalse() {
        when(valueOperations.get(KEY_PREFIX + "b"))
                .thenReturn("{\"enabled\":true,\"percentage\":100,\"blacklist\":[\"blocked\"]}");

        assertFalse(service.isEnabled("b", "blocked"));
        assertTrue(service.isEnabled("b", "ok"));
    }

    // ---- 环境过滤 ----

    @Test
    @DisplayName("开关限定其他环境时不生效")
    void isEnabled_envNotMatched_returnsFalse() {
        when(valueOperations.get(KEY_PREFIX + "prod-only"))
                .thenReturn("{\"enabled\":true,\"percentage\":100,\"environments\":[\"prod\"]}");

        assertFalse(service.isEnabled("prod-only", "u1"));
    }

    @Test
    @DisplayName("开关限定当前环境时生效")
    void isEnabled_envMatched_returnsTrue() {
        when(valueOperations.get(KEY_PREFIX + "dev-only"))
                .thenReturn("{\"enabled\":true,\"percentage\":100,\"environments\":[\"dev\"]}");

        assertTrue(service.isEnabled("dev-only", "u1"));
    }

    // ---- 开关操作 ----

    @Test
    @DisplayName("toggle 写入启用配置并设置 30 天过期")
    void toggle_enablesFlag() {
        when(valueOperations.get(KEY_PREFIX + "t"))
                .thenReturn("{\"enabled\":false,\"percentage\":0}");

        service.toggle("t", true);

        verify(valueOperations).set(eq(KEY_PREFIX + "t"), contains("\"enabled\":true"),
                eq(30L), eq(TimeUnit.DAYS));
    }

    // ---- 本地缓存 ----

    @Test
    @DisplayName("5 秒内二次读取命中本地缓存不访问 Redis")
    void isEnabled_secondCall_usesCache() {
        when(valueOperations.get(KEY_PREFIX + "c"))
                .thenReturn("{\"enabled\":true,\"percentage\":100}");

        assertTrue(service.isEnabled("c", "u1"));
        assertTrue(service.isEnabled("c", "u1"));

        verify(valueOperations, times(1)).get(KEY_PREFIX + "c");
    }

    @Test
    @DisplayName("clearCache 清除本地缓存")
    void clearCache_removesLocalEntries() {
        when(valueOperations.get(KEY_PREFIX + "cc"))
                .thenReturn("{\"enabled\":true,\"percentage\":100}");

        service.isEnabled("cc", "u1");
        service.clearCache();
        service.isEnabled("cc", "u1");

        verify(valueOperations, times(2)).get(KEY_PREFIX + "cc");
    }

    // ---- FlagConfig 内部类 ----

    @Test
    @DisplayName("FlagConfig 默认字段值")
    void flagConfig_defaults() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        assertFalse(config.enabled);
        assertEquals(0, config.percentage);
        assertNotNull(config.whitelist);
        assertNotNull(config.blacklist);
        assertNotNull(config.environments);
    }

    @Test
    @DisplayName("FlagConfig isEnvironmentEnabled 空列表放行任意环境")
    void flagConfig_emptyEnvironments_allowsAny() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        assertTrue(config.isEnvironmentEnabled("any"));
    }

    @Test
    @DisplayName("FlagConfig isEnvironmentEnabled 匹配/不匹配")
    void flagConfig_environmentMatch() {
        FeatureFlagService.FlagConfig config = new FeatureFlagService.FlagConfig();
        config.environments.add("prod");
        assertFalse(config.isEnvironmentEnabled("dev"));
        assertTrue(config.isEnvironmentEnabled("prod"));
    }
}
