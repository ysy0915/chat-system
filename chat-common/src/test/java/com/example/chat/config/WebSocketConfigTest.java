package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketConfigTest {

    @Test
    @DisplayName("构造函数成功")
    void testConstructor() {
        assertDoesNotThrow(() -> new WebSocketConfig());
    }

    @Test
    @DisplayName("createWebSocketContainer")
    void testCreateWebSocketContainer() {
        WebSocketConfig config = new WebSocketConfig();
        assertNotNull(config.createWebSocketContainer());
    }
}
