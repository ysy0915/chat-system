package com.example.chat.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * IP 限流 + 黑名单 + UA 过滤拦截器
 *
 * 防护策略：
 * 1. IP 黑名单：直接拒绝（60 秒内超过 100 次请求自动拉黑 10 分钟）
 * 2. IP 限流：单 IP 每分钟最多 60 次请求（全站）
 * 3. UA 过滤：拦截无 User-Agent 或已知爬虫 UA
 * 4. 敏感接口限流：登录/注册每分钟最多 5 次
 */
@Component
public class IpRateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IpRateLimitInterceptor.class);

    private final StringRedisTemplate redis;

    // 全站限流：单 IP 每分钟 600 次（多页面+多标签同时访问）
    private static final int GLOBAL_PER_MINUTE = 600;
    // 敏感接口限流：单 IP 每分钟 10 次（登录/注册）
    private static final int SENSITIVE_PER_MINUTE = 10;
    // 自动拉黑阈值：60 秒内超过 1000 次
    private static final int BLACKLIST_THRESHOLD = 1000;
    // 拉黑时长：10 分钟
    private static final Duration BLACKLIST_TTL = Duration.ofMinutes(10);

    // 已知爬虫 UA 关键词（不区分大小写）
    private static final Set<String> BLOCKED_UA_KEYWORDS = Set.of(
            "scrapy", "python-requests", "curl", "wget", "httpclient",
            "okhttp", "java/", "go-http-client", "libwww", "phantomjs",
            "headless", "selenium", "bot", "spider", "crawler"
    );

    // 允许的爬虫（白名单，避免误杀搜索引擎）
    private static final Set<String> ALLOWED_UA_KEYWORDS = Set.of(
            "googlebot", "baiduspider", "bingbot", "sogou", "360spider",
            "bytespider", "yandexbot"
    );

    private static final String KEY_GLOBAL = "ip:rate:global:";
    private static final String KEY_SENSITIVE = "ip:rate:sensitive:";
    private static final String KEY_BLACKLIST = "ip:blacklist:";
    private static final String KEY_REQUEST_COUNT = "ip:count:";

    public IpRateLimitInterceptor(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 拦截入口：依次执行黑名单检查、UA 过滤、自动拉黑检查、敏感接口限流、全局限流。
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param handler 处理器
     * @return true 放行；false 拦截（已写入错误响应）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();

        // 1. 黑名单检查
        if (isBlacklisted(ip)) {
            return reject(response, 429, "您的IP已被临时封禁，请稍后再试");
        }

        // 2. UA 过滤（仅对 API 请求生效，放行静态资源和搜索引擎）
        if (uri.startsWith("/api/") && isBlockedUA(request.getHeader("User-Agent"))) {
            return reject(response, 403, "访问被拒绝");
        }

        // 3. 请求计数 + 自动拉黑检查
        if (exceedsBlacklistThreshold(ip, response)) {
            return false;
        }

        // 4. 敏感接口限流（登录/注册）
        if (isSensitiveEndpoint(uri)
                && !checkRate(KEY_SENSITIVE + ip, SENSITIVE_PER_MINUTE, Duration.ofMinutes(1))) {
            return reject(response, 429, "操作过于频繁，请1分钟后再试");
        }

        // 5. 全局限流
        if (!checkRate(KEY_GLOBAL + ip, GLOBAL_PER_MINUTE, Duration.ofMinutes(1))) {
            return reject(response, 429, "请求过于频繁，请稍后再试");
        }

        return true;
    }

    /**
     * 写入拦截响应并返回 false。
     * @param response HTTP 响应
     * @param status HTTP 状态码
     * @param message 错误消息（写入 JSON body 的 error 字段）
     * @return 固定返回 false（拦截请求）
     */
    private boolean reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
        return false;
    }

    /**
     * 请求计数并判断是否超过自动拉黑阈值；超过则拉黑并写入响应。
     * @param ip 客户端 IP
     * @param response HTTP 响应（用于写入拉黑提示）
     * @return true 表示已超过阈值并已拉黑（调用方应返回 false）；false 表示未超过
     */
    private boolean exceedsBlacklistThreshold(String ip, HttpServletResponse response) throws Exception {
        String countKey = KEY_REQUEST_COUNT + ip;
        Long count = redis.opsForValue().increment(countKey);
        if (count != null && count == 1) {
            redis.expire(countKey, Duration.ofSeconds(60));
        }
        if (count != null && count > BLACKLIST_THRESHOLD) {
            blacklist(ip, "请求频率异常: 60秒内" + count + "次");
            reject(response, 429, "请求频率异常，IP已被临时封禁10分钟");
            return true;
        }
        return false;
    }

    /**
     * 限流计数检查：首次访问设置过期时间，超过限额返回 false。
     * Redis 异常时放行（fail-open）。
     * @param key Redis 计数 key
     * @param limit 限流上限
     * @param ttl 计数窗口时长
     * @return true 允许访问；false 已超限
     */
    private boolean checkRate(String key, int limit, Duration ttl) {
        try {
            Long current = redis.opsForValue().increment(key);
            if (current != null && current == 1) {
                redis.expire(key, ttl);
            }
            return current == null || current <= limit;
        } catch (Exception e) {
            // Redis 异常时放行（fail-open）
            return true;
        }
    }

    /**
     * 检查 IP 是否在黑名单中。Redis 异常时视为未拉黑（fail-open）。
     */
    private boolean isBlacklisted(String ip) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_BLACKLIST + ip));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 将 IP 加入黑名单并记录日志。
     * @param ip 客户端 IP
     * @param reason 拉黑原因（写入 Redis value）
     */
    private void blacklist(String ip, String reason) {
        try {
            redis.opsForValue().set(KEY_BLACKLIST + ip, reason, BLACKLIST_TTL);
            log.warn("IP 已被自动拉黑: {} 原因: {}", ip, reason);
        } catch (Exception e) {
            log.error("拉黑 IP 失败: {}", ip, e);
        }
    }

    /**
     * 判断 UA 是否被拦截。无 UA 直接拦截；白名单优先放行搜索引擎。
     * @param ua User-Agent 头（可为 null）
     * @return true 表示应拦截；false 表示放行
     */
    private boolean isBlockedUA(String ua) {
        if (ua == null || ua.isBlank()) return true;
        String lower = ua.toLowerCase(Locale.ROOT);
        // 白名单优先
        for (String allowed : ALLOWED_UA_KEYWORDS) {
            if (lower.contains(allowed)) return false;
        }
        for (String blocked : BLOCKED_UA_KEYWORDS) {
            if (lower.contains(blocked)) return true;
        }
        return false;
    }

    /**
     * 判断 URI 是否为敏感接口（登录/注册）。
     */
    private boolean isSensitiveEndpoint(String uri) {
        return uri.startsWith("/api/v1/auth/login")
                || uri.startsWith("/api/v1/auth/register")
                || uri.startsWith("/api/v1/sql/login")
                || uri.startsWith("/api/v1/monitor/login");
    }

    /**
     * 获取客户端真实 IP：依次取 X-Forwarded-For 首段、X-Real-IP，最后回退到 RemoteAddr。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // X-Forwarded-For 可能有多个，取第一个
            return ip.split(",")[0].trim();
        }
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddr();
    }

    /** 手动拉黑 IP（供管理接口调用） */
    public void manualBlacklist(String ip, String reason, Duration ttl) {
        // blacklist() 已设置默认 BLACKLIST_TTL，外部传入 ttl 时覆盖
        if (ttl != null) {
            redis.opsForValue().set(KEY_BLACKLIST + ip, reason, ttl);
        } else {
            blacklist(ip, reason);
        }
        log.warn("IP 已手动拉黑: {} 原因: {} ttl={}", ip, reason, ttl);
    }

    /** 解封 IP */
    public void unblacklist(String ip) {
        redis.delete(KEY_BLACKLIST + ip);
        redis.delete(KEY_REQUEST_COUNT + ip);
        log.info("IP 已解封: {}", ip);
    }

    /** 检查 IP 是否在黑名单（供外部调用） */
    public boolean isIpBlocked(String ip) {
        return isBlacklisted(ip);
    }
}
