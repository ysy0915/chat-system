package com.example.chat.controller;

import com.example.chat.security.AdminAuthUtil;
import com.example.chat.security.IpRateLimitInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * IP 限流管理接口（需管理员密码）
 */
@Tag(name = "IP 管理", description = "IP 黑名单管理和请求统计（需 Admin 权限）")
@RestController
@RequestMapping("/api/v1/admin/ip")
public class IpAdminController {

    private final IpRateLimitInterceptor interceptor;
    private final StringRedisTemplate redis;
    private final AdminAuthUtil adminAuthUtil;

    public IpAdminController(IpRateLimitInterceptor interceptor, StringRedisTemplate redis, AdminAuthUtil adminAuthUtil) {
        this.interceptor = interceptor;
        this.redis = redis;
        this.adminAuthUtil = adminAuthUtil;
    }

    private boolean checkAuth(String password) {
        return adminAuthUtil.checkMonitorPassword(password);
    }

    /** 查看当前所有被拉黑的 IP */
    @Operation(summary = "查看 IP 黑名单", description = "列出所有被拉黑的 IP 及其封禁原因和剩余时间")
    @GetMapping("/blacklist")
    public ResponseEntity<?> listBlacklist(@RequestHeader("X-Admin-Password") String password) {
        if (!checkAuth(password)) return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        Set<String> keys = redis.keys("ip:blacklist:*");
        Map<String, String> result = new HashMap<>();
        if (keys != null) {
            for (String key : keys) {
                String ip = key.substring("ip:blacklist:".length());
                String reason = redis.opsForValue().get(key);
                Long ttl = redis.getExpire(key);
                result.put(ip, (reason != null ? reason : "unknown") + " | TTL: " + (ttl != null ? ttl : -1) + "s");
            }
        }
        return ResponseEntity.ok(result);
    }

    /** 手动拉黑 IP */
    @Operation(summary = "手动拉黑 IP", description = "将指定 IP 加入黑名单，可设置封禁原因和时长（分钟）")
    @PostMapping("/blacklist/{ip}")
    public ResponseEntity<?> blacklist(@PathVariable String ip,
                                       @RequestHeader("X-Admin-Password") String password,
                                       @RequestBody(required = false) Map<String, Object> body) {
        if (!checkAuth(password)) return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        String reason = body != null ? String.valueOf(body.getOrDefault("reason", "手动拉黑")) : "手动拉黑";
        int minutes = body != null ? (int) body.getOrDefault("minutes", 60) : 60;
        interceptor.manualBlacklist(ip, reason, Duration.ofMinutes(minutes));
        return ResponseEntity.ok(Map.of("ok", true, "ip", ip, "minutes", minutes));
    }

    /** 解封 IP */
    @Operation(summary = "解封 IP", description = "移除指定 IP 的黑名单状态")
    @DeleteMapping("/blacklist/{ip}")
    public ResponseEntity<?> unblacklist(@PathVariable String ip,
                                         @RequestHeader("X-Admin-Password") String password) {
        if (!checkAuth(password)) return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        interceptor.unblacklist(ip);
        return ResponseEntity.ok(Map.of("ok", true, "ip", ip));
    }

    /** 查看某 IP 的当前请求计数 */
    @Operation(summary = "IP 请求统计", description = "查看某 IP 的请求计数、封禁状态、全局速率")
    @GetMapping("/stats/{ip}")
    public ResponseEntity<?> ipStats(@PathVariable String ip,
                                     @RequestHeader("X-Admin-Password") String password) {
        if (!checkAuth(password)) return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        Map<String, Object> stats = new HashMap<>();
        stats.put("ip", ip);
        stats.put("blocked", interceptor.isIpBlocked(ip));
        String count = redis.opsForValue().get("ip:count:" + ip);
        stats.put("requestCount60s", count != null ? Long.parseLong(count) : 0);
        String globalRate = redis.opsForValue().get("ip:rate:global:" + ip);
        stats.put("globalRate60s", globalRate != null ? Long.parseLong(globalRate) : 0);
        return ResponseEntity.ok(stats);
    }
}
