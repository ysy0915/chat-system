package com.example.chat.config;

import com.example.chat.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WebSocketConfigTest {

    private WebSocketConfig newConfig() {
        return new WebSocketConfig(mock(JwtUtil.class));
    }

    @Test
    @DisplayName("构造函数成功")
    void testConstructor() {
        assertDoesNotThrow(this::newConfig);
    }

    @Test
    @DisplayName("createWebSocketContainer")
    void testCreateWebSocketContainer() {
        WebSocketConfig config = newConfig();
        assertNotNull(config.createWebSocketContainer());
    }
}
