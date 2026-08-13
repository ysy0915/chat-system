package com.example.chat.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ErrorType.fromException 错误分类测试（覆盖 7 个分支）。
 */
@DisplayName("ErrorType.fromException 错误分类")
class ErrorTypeTest {

    @Test
    @DisplayName("超时信息归类 TIMEOUT")
    void fromException_timeout_classifiesTimeout() {
        assertEquals(ErrorType.TIMEOUT, ErrorType.fromException(new RuntimeException("read timed out")));
        assertEquals(ErrorType.TIMEOUT, ErrorType.fromException(new RuntimeException("socket timeout")));
        assertEquals(ErrorType.TIMEOUT, ErrorType.fromException(new RuntimeException("请求超时")));
    }

    @Test
    @DisplayName("限流信息归类 RATE_LIMIT")
    void fromException_rateLimit_classifiesRateLimit() {
        assertEquals(ErrorType.RATE_LIMIT, ErrorType.fromException(new RuntimeException("429 Too Many Requests")));
        assertEquals(ErrorType.RATE_LIMIT, ErrorType.fromException(new RuntimeException("rate limit exceeded")));
        assertEquals(ErrorType.RATE_LIMIT, ErrorType.fromException(new RuntimeException("触发限流")));
    }

    @Test
    @DisplayName("鉴权信息归类 AUTH_FAILED")
    void fromException_authFailed_classifiesAuthFailed() {
        assertEquals(ErrorType.AUTH_FAILED, ErrorType.fromException(new RuntimeException("401 Unauthorized")));
        assertEquals(ErrorType.AUTH_FAILED, ErrorType.fromException(new RuntimeException("authentication failed")));
        assertEquals(ErrorType.AUTH_FAILED, ErrorType.fromException(new RuntimeException("鉴权失败")));
    }

    @Test
    @DisplayName("模型缺失信息归类 MODEL_NOT_FOUND")
    void fromException_modelNotFound_classifiesModelNotFound() {
        assertEquals(ErrorType.MODEL_NOT_FOUND, ErrorType.fromException(new RuntimeException("model not found")));
        assertEquals(ErrorType.MODEL_NOT_FOUND, ErrorType.fromException(new RuntimeException("404 Not Found")));
        assertEquals(ErrorType.MODEL_NOT_FOUND, ErrorType.fromException(new RuntimeException("model does not exist")));
    }

    @Test
    @DisplayName("网络错误归类 NETWORK_ERROR")
    void fromException_networkError_classifiesNetworkError() {
        assertEquals(ErrorType.NETWORK_ERROR, ErrorType.fromException(new RuntimeException("Connection refused")));
        assertEquals(ErrorType.NETWORK_ERROR, ErrorType.fromException(new RuntimeException("network unreachable")));
        assertEquals(ErrorType.NETWORK_ERROR, ErrorType.fromException(new RuntimeException("网络连接失败")));
    }

    @Test
    @DisplayName("解析错误归类 PARSE_ERROR")
    void fromException_parseError_classifiesParseError() {
        assertEquals(ErrorType.PARSE_ERROR, ErrorType.fromException(new RuntimeException("JSON parse error")));
        assertEquals(ErrorType.PARSE_ERROR, ErrorType.fromException(new RuntimeException("响应解析失败")));
    }

    @Test
    @DisplayName("null 异常与未知信息归类 UNKNOWN")
    void fromException_nullOrUnknown_classifiesUnknown() {
        assertEquals(ErrorType.UNKNOWN, ErrorType.fromException(null));
        assertEquals(ErrorType.UNKNOWN, ErrorType.fromException(new RuntimeException()));
        assertEquals(ErrorType.UNKNOWN, ErrorType.fromException(new RuntimeException("some random message")));
    }
}
