package com.example.chat.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CastleSiegeBattlefieldController 类存在性和结构验证测试。
 */
class CastleSiegeBattlefieldControllerTest {

    @Test
    void shouldHaveClass() {
        Class<?> clazz = CastleSiegeBattlefieldController.class;
        assertNotNull(clazz, "CastleSiegeBattlefieldController should exist");
    }

    @Test
    void shouldHaveJoinMethod() throws Exception {
        Method method = CastleSiegeBattlefieldController.class.getDeclaredMethod("join", String.class, java.util.Map.class);
        assertNotNull(method, "join method should exist");
    }

    @Test
    void shouldHaveUpdateMethod() throws Exception {
        Method method = CastleSiegeBattlefieldController.class.getDeclaredMethod("update", String.class, java.util.Map.class);
        assertNotNull(method, "update method should exist");
    }

    @Test
    void shouldHaveLeaveMethod() throws Exception {
        Method method = CastleSiegeBattlefieldController.class.getDeclaredMethod("leave", String.class, java.util.Map.class);
        assertNotNull(method, "leave method should exist");
    }

    @Test
    void shouldHaveRestControllerAnnotation() {
        assertTrue(CastleSiegeBattlefieldController.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestController.class),
                "Should have @RestController annotation");
    }
}
