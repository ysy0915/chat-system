package com.example.chat.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 认证工具类：从请求头中统一提取 JWT 用户信息，
 * 消除各 Controller 中重复的 extractUserId 逻辑。
 */
public final class AuthUtils {
    private static final Logger log = LoggerFactory.getLogger(AuthUtils.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private AuthUtils() {
    }

    /** 从 Authorization 请求头中提取用户 ID，无效或未登录返回 null */
    public static Long extractUserId(HttpServletRequest request, JwtUtil jwtUtil) {
        if (request == null || jwtUtil == null) return null;
        return extractUserId(request.getHeader("Authorization"), jwtUtil);
    }

    /** 从 Bearer Token 字符串中提取用户 ID，无效或未登录返回 null */
    public static Long extractUserId(String authHeader, JwtUtil jwtUtil) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) return null;
        String token = authHeader.substring(BEARER_PREFIX.length());
        try {
            if (!jwtUtil.validateToken(token)) return null;
            return jwtUtil.getUserId(token);
        } catch (Exception e) {
            log.debug("[AUTH] token 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
