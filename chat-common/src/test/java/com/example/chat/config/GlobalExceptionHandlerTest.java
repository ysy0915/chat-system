package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("构造函数成功")
    void testConstructor() {
        assertDoesNotThrow(() -> new GlobalExceptionHandler());
    }
}
