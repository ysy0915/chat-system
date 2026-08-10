package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErrorTypeTest {

    @Test
    void shouldBeEnum() {
        assertTrue(ErrorType.class.isEnum(), "ErrorType should be an enum");
    }

    @Test
    void shouldContainExpectedValues() {
        ErrorType[] values = ErrorType.values();
        assertTrue(values.length >= 1, "ErrorType should have at least one value");
    }
}
