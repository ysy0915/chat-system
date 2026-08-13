package com.example.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LLMCallExceptionTest {

    @Test
    @DisplayName("仅消息构造")
    void testMessageOnly() {
        LLMCallException e = new LLMCallException("api error");
        assertEquals("api error", e.getMessage());
        assertEquals(-1, e.getHttpStatus());
        assertNull(e.getModel());
    }

    @Test
    @DisplayName("消息+原因构造")
    void testMessageWithCause() {
        RuntimeException cause = new RuntimeException("io");
        LLMCallException e = new LLMCallException("api error", cause);
        assertEquals("api error", e.getMessage());
        assertEquals(-1, e.getHttpStatus());
        assertEquals(cause, e.getCause());
    }

    @Test
    @DisplayName("HTTP状态码构造")
    void testHttpStatusConstructor() {
        LLMCallException e = new LLMCallException(429, "rate limit");
        assertEquals("rate limit", e.getMessage());
        assertEquals(429, e.getHttpStatus());
        assertNull(e.getModel());
    }

    @Test
    @DisplayName("模型+消息+原因构造")
    void testModelConstructor() {
        RuntimeException cause = new RuntimeException("timeout");
        LLMCallException e = new LLMCallException("gpt-4", "timeout", cause);
        assertEquals("timeout", e.getMessage());
        assertEquals("gpt-4", e.getModel());
        assertEquals(cause, e.getCause());
    }
}
