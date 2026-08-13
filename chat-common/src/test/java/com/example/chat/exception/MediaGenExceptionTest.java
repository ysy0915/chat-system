package com.example.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MediaGenExceptionTest {

    @Test
    @DisplayName("仅消息构造")
    void testMessageOnly() {
        MediaGenException e = new MediaGenException("gen error");
        assertEquals("gen error", e.getMessage());
    }

    @Test
    @DisplayName("消息+原因构造")
    void testMessageWithCause() {
        RuntimeException cause = new RuntimeException("oss error");
        MediaGenException e = new MediaGenException("gen error", cause);
        assertEquals("gen error", e.getMessage());
        assertEquals(cause, e.getCause());
    }
}
