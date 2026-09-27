package com.example.chat.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内部接口令牌校验：/internal/** 仅放行携带正确 {@link #HEADER} 的内部调用方。
 *
 * <p>此前 /internal/** 仅靠网络隔离（Nginx 不转发），一旦端口映射/防火墙配置失误，
 * 内部接口（含 /internal/tools 管理写接口）将直接暴露公网。加上应用层共享令牌后，
 * 即使网络隔离失效，无令牌的请求也会被拒绝。</p>
 *
 * <p>令牌未配置时放行并告警一次（兼容本地开发）；生产环境必须通过环境变量
 * {@code INTERNAL_API_TOKEN} 配置（web 与 core 使用同一值，由 StartupConfigValidator 自检）。</p>
 */
@Component
public class InternalApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiTokenFilter.class);

    /** 内部调用方需携带的请求头 */
    public static final String HEADER = "X-Internal-Token";

    private final String internalToken;
    private final AtomicBoolean unconfiguredWarned = new AtomicBoolean(false);

    public InternalApiTokenFilter(
            @Value("${app.security.internal-token:${INTERNAL_API_TOKEN:}}") String internalToken) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅保护 /internal/**，其余路径（各模块自身业务）不受影响
        return !request.getRequestURI().startsWith("/internal");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (internalToken.isEmpty()) {
            if (unconfiguredWarned.compareAndSet(false, true)) {
                log.warn("[InternalAuth] INTERNAL_API_TOKEN 未配置，/internal/** 处于无防护状态（本地开发可接受，生产必须配置）");
            }
            chain.doFilter(request, response);
            return;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("[InternalAuth] 拒绝 /internal 访问: uri={}, remote={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"ok\":false,\"error\":\"internal token invalid\"}");
    }
}
