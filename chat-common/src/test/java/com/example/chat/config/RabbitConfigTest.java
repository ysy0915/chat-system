package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RabbitConfigTest {

    @Test
    @DisplayName("常量值正确")
    void testConstants() {
        assertEquals("chat.requests", RabbitConfig.CHAT_REQUESTS_QUEUE);
        assertEquals("chat.exchange", RabbitConfig.CHAT_EXCHANGE);
        assertEquals("chat.request", RabbitConfig.CHAT_ROUTING_KEY);
    }

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.config.RabbitConfig");
        });
    }
}
