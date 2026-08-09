package com.example.chat.featureflag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 特性开关服务（本地化Harness Feature Flags等效方案）
 *
 * 支持：
 * - 按百分比灰度发布（0-100%）
 * - 按用户ID白名单/黑名单
 * - 按环境开关（dev/staging/prod）
 * - 实时生效（Redis存储，无需重启）
 * - 本地缓存（5秒TTL，减少Redis请求）
 *
 * 使用方式：
 *   if (featureFlag.isEnabled("knowledge-graph", userId)) { ... }
 *
 * Redis结构：
 *   feature:flag:{name} → JSON { enabled, percentage, whitelist, blacklist, environments }
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);
    private static final String KEY_PREFIX = "feature:flag:";
    private static final long CACHE_TTL_MS = 5000; // 本地缓存5秒

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String currentEnv;

    // 本地缓存
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public FeatureFlagService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                              org.springframework.core.env.Environment env) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        String[] activeProfiles = env.getActiveProfiles();
        this.currentEnv = activeProfiles.length > 0 ? activeProfiles[0] : "default";
    }

    private static class CacheEntry {
        final FlagConfig config;
        final long cachedAt;
        CacheEntry(FlagConfig config, long cachedAt) {
            this.config = config;
            this.cachedAt = cachedAt;
        }
        boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    public static class FlagConfig {
        public boolean enabled = false;
        public int percentage = 0; // 0-100，灰度百分比
        public Set<String> whitelist = new HashSet<>(); // 白名单用户ID
        public Set<String> blacklist = new HashSet<>(); // 黑名单用户ID
        public Set<String> environments = new HashSet<>(); // 启用的环境（dev/staging/prod）

        public boolean isEnvironmentEnabled(String env) {
            return environments.isEmpty() || environments.contains(env);
        }
    }

    /**
     * 判断特性是否对指定用户启用
     */
    public boolean isEnabled(String flagName, String userId) {
        FlagConfig config = getConfig(flagName);
        if (config == null || !config.enabled) return false;

        // 环境检查
        if (!config.isEnvironmentEnabled(currentEnv)) return false;

        // 黑名单优先
        if (userId != null && config.blacklist.contains(userId)) return false;

        // 白名单直接通过
        if (userId != null && config.whitelist.contains(userId)) return true;

        // 百分比灰度
        if (config.percentage >= 100) return true;
        if (config.percentage <= 0) return false;

        // 基于userId hash计算是否在灰度范围内（保证同一用户结果稳定）
        if (userId == null) return config.percentage >= 50; // 匿名用户50%阈值
        int hash = Math.abs(userId.hashCode() % 100);
        return hash < config.percentage;
    }

    /**
     * 匿名用户判断（无userId）
     */
    public boolean isEnabled(String flagName) {
        return isEnabled(flagName, null);
    }

    /**
     * 获取配置（带本地缓存）
     */
    private FlagConfig getConfig(String flagName) {
        CacheEntry entry = cache.get(flagName);
        if (entry != null && !entry.isExpired()) {
            return entry.config;
        }

        // 从Redis加载
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + flagName);
            FlagConfig config;
            if (json != null) {
                config = objectMapper.readValue(json, FlagConfig.class);
            } else {
                config = new FlagConfig(); // 默认关闭
            }
            cache.put(flagName, new CacheEntry(config, System.currentTimeMillis()));
            return config;
        } catch (Exception e) {
            log.warn("[FeatureFlag] 读取 {} 失败: {}", flagName, e.getMessage());
            return new FlagConfig();
        }
    }

    /**
     * 设置特性开关配置（管理接口用）
     */
    public void setConfig(String flagName, FlagConfig config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            redisTemplate.opsForValue().set(KEY_PREFIX + flagName, json);
            cache.remove(flagName); // 清除本地缓存
            log.info("[FeatureFlag] {} 已更新: enabled={}, percentage={}, env={}",
                    flagName, config.enabled, config.percentage, config.environments);
        } catch (Exception e) {
            log.error("[FeatureFlag] 保存 {} 失败: {}", flagName, e.getMessage());
        }
    }

    /**
     * 快速开关（不改其他配置）
     */
    public void toggle(String flagName, boolean enabled) {
        FlagConfig config = getConfig(flagName);
        if (config == null) config = new FlagConfig();
        config.enabled = enabled;
        setConfig(flagName, config);
    }

    /**
     * 设置灰度百分比
     */
    public void setPercentage(String flagName, int percentage) {
        FlagConfig config = getConfig(flagName);
        if (config == null) config = new FlagConfig();
        config.percentage = Math.max(0, Math.min(100, percentage));
        config.enabled = true;
        setConfig(flagName, config);
    }

    /**
     * 添加白名单用户
     */
    public void addWhitelist(String flagName, String userId) {
        FlagConfig config = getConfig(flagName);
        if (config == null) config = new FlagConfig();
        config.whitelist.add(userId);
        config.enabled = true;
        setConfig(flagName, config);
    }

    /**
     * 获取所有开关状态（监控用）
     */
    public Map<String, Object> getAllStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                String name = key.substring(KEY_PREFIX.length());
                FlagConfig config = getConfig(name);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("enabled", config.enabled);
                info.put("percentage", config.percentage);
                info.put("whitelist_size", config.whitelist.size());
                info.put("blacklist_size", config.blacklist.size());
                info.put("environments", config.environments);
                status.put(name, info);
            }
        }
        return status;
    }

    /**
     * 清除本地缓存（强制下次从Redis读取）
     */
    public void clearCache() {
        cache.clear();
    }
}
