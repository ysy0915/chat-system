package com.example.chat.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculationExceptionTest {

    @Test
    @DisplayName("仅消息构造")
    void testMessageOnly() {
        CalculationException e = new CalculationException("calc error");
        assertEquals("calc error", e.getMessage());
    }

    @Test
    @DisplayName("消息+原因构造")
    void testMessageWithCause() {
        RuntimeException cause = new RuntimeException("overflow");
        CalculationException e = new CalculationException("calc error", cause);
        assertEquals("calc error", e.getMessage());
        assertEquals(cause, e.getCause());
    }
}
