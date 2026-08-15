package com.example.chat.llm.config;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * 知识库管理 API 管理员鉴权拦截器。
 *
 * <p>拦截 {@code /api/v1/rag/**}（{@link com.example.chat.llm.rag.controller.KnowledgeController}），
 * 统一校验 admin 角色，消除各接口方法内的重复样板。standalone 模式
 * （{@code app.security.enabled=false}）直接放行。</p>
 *
 * <p>行为与原 {@code KnowledgeController.checkAdmin} 完全等价：
 * 无/非 Bearer Token → 401「请先登录」；Token 过期/无效 → 401「登录已过期，请重新登录」；
 * 非 admin → 403「仅管理员可操作知识库」。</p>
 */
public class RagAdminAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final boolean securityEnabled;

    public RagAdminAuthInterceptor(JwtUtil jwtUtil, ObjectMapper objectMapper, boolean securityEnabled) {
        this.jwtUtil = jwtUtil;
        this.objectMapper = objectMapper;
        this.securityEnabled = securityEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // standalone 模式（app.security.enabled=false）关闭认证，直接放行
        if (!securityEnabled) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return reject(response, 401, ErrorCode.UNAUTHORIZED, "请先登录");
        }
        String role = AuthUtils.extractRole(authHeader, jwtUtil);
        if (role == null) {
            return reject(response, 401, ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!"admin".equals(role)) {
            return reject(response, 403, ErrorCode.FORBIDDEN, "仅管理员可操作知识库");
        }
        return true;
    }

    private boolean reject(HttpServletResponse response, int status, ErrorCode code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(code, message));
        return false;
    }
}
