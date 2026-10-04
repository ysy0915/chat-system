package com.example.chat.exception;

/**
 * 服务间代理调用透传异常：上游服务（chat-llm / chat-core）返回非 2xx 时，
 * 携带其原始 HTTP 状态码与响应体抛出，由 {@code GlobalExceptionHandler} 原样透传给前端。
 *
 * <p>背景（2026-10-04 生产案例）：知识库管理代理调用 chat-llm 返回
 * 403「仅管理员可操作知识库」，被 CoreClient 的 RestTemplate 异常机制
 * 一路抛到全局兜底、伪装成 500「服务器内部错误」——鉴权语义完全丢失，
 * 误导排障方向（前端本有 403 专属提示页，却因状态码丢失无法命中）。
 * 上游 4xx/5xx 是业务语义（401 登录过期、403 权限不足、400 参数错误），必须透传。</p>
 */
public class UpstreamHttpException extends RuntimeException {

    private final int httpStatus;
    private final String upstreamBody;

    public UpstreamHttpException(int httpStatus, String upstreamBody) {
        super("上游服务返回 " + httpStatus + ": " + upstreamBody);
        this.httpStatus = httpStatus;
        this.upstreamBody = upstreamBody;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** 上游原始响应体（JSON 字符串），可能为空串（如网关层裸 502） */
    public String getUpstreamBody() {
        return upstreamBody;
    }
}
