package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigTest {

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.config.SecurityConfig");
        });
    }

    @Test
    @DisplayName("类方法验证 - 有 passwordEncoder 方法")
    void testHasPasswordEncoderMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.SecurityConfig");
        assertNotNull(clazz.getDeclaredMethod("passwordEncoder"));
    }

    @Test
    @DisplayName("类方法验证 - 有 filterChain 方法")
    void testHasFilterChainMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.SecurityConfig");
        assertNotNull(clazz.getDeclaredMethod("filterChain",
                org.springframework.security.config.annotation.web.builders.HttpSecurity.class));
    }

    @Test
    @DisplayName("类注解验证 - 有 Configuration 注解")
    void testHasConfigurationAnnotation() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.config.SecurityConfig");
        assertNotNull(clazz.getAnnotation(org.springframework.context.annotation.Configuration.class));
    }
}
