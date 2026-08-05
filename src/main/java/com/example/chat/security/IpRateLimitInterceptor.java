package com.example.chat.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
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

    // 全站限流：单 IP 每分钟 60 次
    private static final int GLOBAL_PER_MINUTE = 60;
    // 敏感接口限流：单 IP 每分钟 5 次（登录/注册）
    private static final int SENSITIVE_PER_MINUTE = 5;
    // 自动拉黑阈值：60 秒内超过 100 次
    private static final int BLACKLIST_THRESHOLD = 100;
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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        String uri = request.getRequestURI();

        // 1. 检查黑名单
        if (isBlacklisted(ip)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"您的IP已被临时封禁，请稍后再试\"}");
            return false;
        }

        // 2. UA 过滤（仅对 API 请求生效，放行静态资源和搜索引擎）
        if (uri.startsWith("/api/")) {
            String ua = request.getHeader("User-Agent");
            if (isBlockedUA(ua)) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"访问被拒绝\"}");
                return false;
            }
        }

        // 3. 请求计数（用于自动拉黑判断）
        String countKey = KEY_REQUEST_COUNT + ip;
        Long count = redis.opsForValue().increment(countKey);
        if (count != null && count == 1) {
            redis.expire(countKey, Duration.ofSeconds(60));
        }
        // 超过阈值自动拉黑
        if (count != null && count > BLACKLIST_THRESHOLD) {
            blacklist(ip, "请求频率异常: 60秒内" + count + "次");
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求频率异常，IP已被临时封禁10分钟\"}");
            return false;
        }

        // 4. 敏感接口限流（登录/注册）
        if (isSensitiveEndpoint(uri)) {
            if (!checkRate(KEY_SENSITIVE + ip, SENSITIVE_PER_MINUTE, Duration.ofMinutes(1))) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"操作过于频繁，请1分钟后再试\"}");
                return false;
            }
        }

        // 5. 全局限流
        if (!checkRate(KEY_GLOBAL + ip, GLOBAL_PER_MINUTE, Duration.ofMinutes(1))) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        return true;
    }

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

    private boolean isBlacklisted(String ip) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_BLACKLIST + ip));
        } catch (Exception e) {
            return false;
        }
    }

    private void blacklist(String ip, String reason) {
        try {
            redis.opsForValue().set(KEY_BLACKLIST + ip, reason, BLACKLIST_TTL);
            log.warn("IP 已被自动拉黑: {} 原因: {}", ip, reason);
        } catch (Exception e) {
            log.error("拉黑 IP 失败: {}", ip, e);
        }
    }

    private boolean isBlockedUA(String ua) {
        if (ua == null || ua.isBlank() || ua.length() < 10) return true;
        String lower = ua.toLowerCase();
        // 白名单优先
        for (String allowed : ALLOWED_UA_KEYWORDS) {
            if (lower.contains(allowed)) return false;
        }
        for (String blocked : BLOCKED_UA_KEYWORDS) {
            if (lower.contains(blocked)) return true;
        }
        return false;
    }

    private boolean isSensitiveEndpoint(String uri) {
        return uri.startsWith("/api/v1/auth/login")
                || uri.startsWith("/api/v1/auth/register")
                || uri.startsWith("/api/v1/sql/login")
                || uri.startsWith("/api/v1/monitor/login");
    }

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
        blacklist(ip, reason);
        if (ttl != null) {
            redis.opsForValue().set(KEY_BLACKLIST + ip, reason, ttl);
        }
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
