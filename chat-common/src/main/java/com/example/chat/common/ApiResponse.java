package com.example.chat.common;

import java.util.Map;

/**
 * 统一响应体工厂。
 *
 * <p>成功：<code>{"ok": true, "data": ...}</code>；
 * 失败：<code>{"ok": false, "code": 400, "error": "..."}</code>。</p>
 *
 * <p>注意：Map.of 不允许 null 值，ok(null) 请使用 {@link #ok()}。</p>
 */
public final class ApiResponse {

    private ApiResponse() {}

    // ── 成功 ──

    public static <T> Map<String, Object> ok(T data) {
        return Map.of("ok", true, "data", data);
    }

    public static Map<String, Object> ok() {
        return Map.of("ok", true);
    }

    /** ok 的语义别名（与 ok 完全等价） */
    public static <T> Map<String, Object> success(T data) {
        return ok(data);
    }

    /** ok 的语义别名（与 ok 完全等价） */
    public static Map<String, Object> success() {
        return ok();
    }

    // ── 失败 ──

    public static Map<String, Object> error(String message) {
        return Map.of("ok", false, "error", message);
    }

    public static Map<String, Object> error(int code, String message) {
        return Map.of("ok", false, "code", code, "error", message);
    }

    /** 按错误码枚举生成错误响应（message 用枚举默认文案） */
    public static Map<String, Object> error(ErrorCode code) {
        return error(code.getCode(), code.getMessage());
    }

    /** 按错误码枚举生成错误响应（自定义 message） */
    public static Map<String, Object> error(ErrorCode code, String message) {
        return error(code.getCode(), message);
    }

    /** error 的语义别名（与 error 完全等价） */
    public static Map<String, Object> fail(String message) {
        return error(message);
    }

    /** error 的语义别名（与 error 完全等价） */
    public static Map<String, Object> fail(ErrorCode code, String message) {
        return error(code, message);
    }
}
