package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrossNodeConfigTest {

    @Test
    @DisplayName("常量 EXCHANGE 值正确")
    void testExchangeConstant() {
        assertEquals("cross-node", CrossNodeConfig.EXCHANGE);
    }

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.config.CrossNodeConfig");
        });
    }
}
