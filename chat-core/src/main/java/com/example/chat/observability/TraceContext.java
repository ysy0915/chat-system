package com.example.chat.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 链路追踪上下文
 * 使用 ThreadLocal 存储当前请求的 traceId
 */
@Component
@ConditionalOnProperty(name = "app.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    /**
     * 开启一条新链路，生成 traceId（UUID 前8位）
     * @return 生成的 traceId
     */
    public String start() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        TRACE_ID.set(traceId);
        return traceId;
    }

    /**
     * 获取当前线程的 traceId
     * @return traceId，未开启时返回 null
     */
    public String get() {
        return TRACE_ID.get();
    }

    /**
     * 清除当前线程的 traceId
     */
    public void clear() {
        TRACE_ID.remove();
    }
}
