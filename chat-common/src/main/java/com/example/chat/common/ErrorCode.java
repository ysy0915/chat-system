package com.example.chat.common;

/**
 * 统一业务错误码。
 *
 * <p>与 HTTP 状态码对齐，用于 ApiResponse 的 code 字段，
 * 消除各 Controller 内联魔法数字。</p>
 */
public enum ErrorCode {

    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证或登录已过期"),
    FORBIDDEN(403, "权限不足"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMITED(429, "请求过于频繁，请稍后再试"),
    INTERNAL_ERROR(500, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
