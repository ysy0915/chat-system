package com.example.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceExceptionTest {

    @Test
    @DisplayName("仅消息构造")
    void testMessageOnly() {
        ChatServiceException e = new ChatServiceException("error");
        assertEquals("error", e.getMessage());
        assertNull(e.getServiceName());
        assertNull(e.getErrorCode());
    }

    @Test
    @DisplayName("消息+原因构造")
    void testMessageWithCause() {
        RuntimeException cause = new RuntimeException("root");
        ChatServiceException e = new ChatServiceException("error", cause);
        assertEquals("error", e.getMessage());
        assertEquals(cause, e.getCause());
    }

    @Test
    @DisplayName("完整构造")
    void testFullConstructor() {
        ChatServiceException e = new ChatServiceException("core", "E001", "error");
        assertEquals("error", e.getMessage());
        assertEquals("core", e.getServiceName());
        assertEquals("E001", e.getErrorCode());
    }

    @Test
    @DisplayName("完整构造+原因")
    void testFullConstructorWithCause() {
        RuntimeException cause = new RuntimeException("root");
        ChatServiceException e = new ChatServiceException("core", "E001", "error", cause);
        assertEquals("error", e.getMessage());
        assertEquals("core", e.getServiceName());
        assertEquals("E001", e.getErrorCode());
        assertEquals(cause, e.getCause());
    }
}
