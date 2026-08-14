package com.example.chat.observability;

import java.util.Locale;

/**
 * 错误类型枚举
 */
public enum ErrorType {
    /** 超时 */
    TIMEOUT,
    /** 限流 */
    RATE_LIMIT,
    /** 鉴权失败 */
    AUTH_FAILED,
    /** 模型不存在 */
    MODEL_NOT_FOUND,
    /** 网络错误 */
    NETWORK_ERROR,
    /** 解析错误 */
    PARSE_ERROR,
    /** 未知错误 */
    UNKNOWN;

    /**
     * 根据异常信息推断错误类型
     */
    @SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
    // 关键字匹配表：每个 if 即一条独立分类规则，拆为 Map 表驱动反而丢失可读性
    public static ErrorType fromException(Exception e) {
        if (e == null || e.getMessage() == null) {
            return UNKNOWN;
        }
        String msg = e.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("超时")) {
            return TIMEOUT;
        }
        if (msg.contains("rate limit") || msg.contains("429") || msg.contains("限流") || msg.contains("too many requests")) {
            return RATE_LIMIT;
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("auth") || msg.contains("unauthorized") || msg.contains("forbidden") || msg.contains("鉴权")) {
            return AUTH_FAILED;
        }
        if (msg.contains("model not found") || msg.contains("404") || msg.contains("does not exist")) {
            return MODEL_NOT_FOUND;
        }
        if (msg.contains("network") || msg.contains("connection") || msg.contains("connect") || msg.contains("unreachable") || msg.contains("refused") || msg.contains("网络")) {
            return NETWORK_ERROR;
        }
        if (msg.contains("parse") || msg.contains("json") || msg.contains("解析")) {
            return PARSE_ERROR;
        }
        return UNKNOWN;
    }
}
