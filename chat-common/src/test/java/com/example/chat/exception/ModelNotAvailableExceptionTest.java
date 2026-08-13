package com.example.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelNotAvailableExceptionTest {

    @Test
    @DisplayName("仅消息构造")
    void testMessageOnly() {
        ModelNotAvailableException e = new ModelNotAvailableException("not found");
        assertEquals("not found", e.getMessage());
    }

    @Test
    @DisplayName("模型+provider构造")
    void testModelProviderConstructor() {
        ModelNotAvailableException e = new ModelNotAvailableException("gpt-5", "openai");
        assertTrue(e.getMessage().contains("gpt-5"));
        assertTrue(e.getMessage().contains("openai"));
        assertEquals("gpt-5", e.getModelName());
        assertEquals("openai", e.getProvider());
    }

    @Test
    @DisplayName("模型+provider+原因构造")
    void testFullConstructor() {
        RuntimeException cause = new RuntimeException("config error");
        ModelNotAvailableException e = new ModelNotAvailableException("gpt-5", "openai", cause);
        assertEquals("gpt-5", e.getModelName());
        assertEquals("openai", e.getProvider());
        assertEquals(cause, e.getCause());
    }
}
