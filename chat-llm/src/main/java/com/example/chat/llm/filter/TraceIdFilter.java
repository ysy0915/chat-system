package com.example.chat.llm.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * <h2>分布式追踪 — TraceId 过滤器</h2>
 *
 * <p>为每个 HTTP 请求生成或继承 TraceId，注入 MDC 和 Response Header，
 * 实现全链路追踪。</p>
 *
 * <h3>行为</h3>
 * <ul>
 *   <li>请求含 X-Trace-Id → 继承上游 TraceId</li>
 *   <li>请求无 X-Trace-Id → 生成新 UUID</li>
 *   <li>响应携带 X-Trace-Id → 下游可继续传递</li>
 *   <li>MDC 设置 traceId → 日志自动携带</li>
 * </ul>
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 获取当前线程的 TraceId（供非 HTTP 上下文使用）。
     */
    public static String currentTraceId() {
        String tid = MDC.get(MDC_KEY);
        return tid != null ? tid : "N/A";
    }
}
