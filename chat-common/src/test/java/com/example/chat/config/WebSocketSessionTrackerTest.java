package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebSocketSessionTrackerTest {

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.config.WebSocketSessionTracker");
        });
    }

    @Test
    @DisplayName("方法验证 - getTotalCount")
    void testHasGetTotalCountMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("getTotalCount"));
    }

    @Test
    @DisplayName("方法验证 - getCount")
    void testHasGetCountMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("getCount", String.class));
    }

    @Test
    @DisplayName("方法验证 - registerUser")
    void testHasRegisterUserMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("registerUser", String.class, String.class, String.class, String.class));
    }

    @Test
    @DisplayName("方法验证 - unregisterUser")
    void testHasUnregisterUserMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("unregisterUser", String.class, String.class));
    }

    @Test
    @DisplayName("方法验证 - handleDisconnect")
    void testHasHandleDisconnectMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("handleDisconnect",
                org.springframework.web.socket.messaging.SessionDisconnectEvent.class));
    }

    @Test
    @DisplayName("方法验证 - touchSession")
    void testHasTouchSessionMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("touchSession", String.class));
    }

    @Test
    @DisplayName("方法验证 - cleanupIdleSessions")
    void testHasCleanupIdleSessionsMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("cleanupIdleSessions"));
    }

    @Test
    @DisplayName("方法验证 - getAllRealCounts")
    void testHasGetAllRealCountsMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("getAllRealCounts"));
    }

    @Test
    @DisplayName("方法验证 - getRealTotalCount")
    void testHasGetRealTotalCountMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.WebSocketSessionTracker");
        assertNotNull(clazz.getDeclaredMethod("getRealTotalCount"));
    }
}
