package com.example.chat.llm.config;

import com.example.chat.llm.filter.TraceIdFilter;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.slf4j.MDC;

/**
 * <h2>gRPC TraceId 拦截器</h2>
 *
 * <p>从 gRPC 请求 metadata 中提取/生成 TraceId 并注入 MDC，
 * 保证 gRPC 调用也纳入分布式追踪。</p>
 */
@GrpcGlobalServerInterceptor
public class TracingGrpcInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String traceId = headers.get(Metadata.Key.of(
                TraceIdFilter.TRACE_HEADER, Metadata.ASCII_STRING_MARSHALLER));

        if (traceId == null || traceId.isBlank()) {
            traceId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(TraceIdFilter.MDC_KEY, traceId);

        // 在响应头中也带上 traceId
        Context ctx = Context.current().withValue(
                Constants.TRACE_ID_KEY, traceId);

        return Contexts.interceptCall(ctx, call, headers, next);
    }

    /**
     * 获取当前 gRPC 上下文的 TraceId。
     */
    public static String currentTraceId() {
        return Constants.TRACE_ID_KEY.get();
    }

    static final class Constants {
        static final Context.Key<String> TRACE_ID_KEY =
                Context.key(TraceIdFilter.TRACE_HEADER);
    }
}
