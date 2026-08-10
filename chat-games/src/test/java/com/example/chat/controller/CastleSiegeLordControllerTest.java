package com.example.chat.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CastleSiegeLordController 类存在性和结构验证测试。
 */
class CastleSiegeLordControllerTest {

    @Test
    void shouldHaveClass() {
        Class<?> clazz = CastleSiegeLordController.class;
        assertNotNull(clazz, "CastleSiegeLordController should exist");
    }

    @Test
    void shouldHaveGetLeaderboardMethod() throws Exception {
        Method method = CastleSiegeLordController.class.getDeclaredMethod("getLeaderboard", int.class);
        assertNotNull(method, "getLeaderboard method should exist");
    }

    @Test
    void shouldHaveSyncLeaderboardMethod() throws Exception {
        Method method = CastleSiegeLordController.class.getDeclaredMethod("syncLeaderboard",
                String.class, java.util.Map.class);
        assertNotNull(method, "syncLeaderboard method should exist");
    }

    @Test
    void shouldHaveRestControllerAnnotation() {
        assertTrue(CastleSiegeLordController.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestController.class),
                "Should have @RestController annotation");
    }

    @Test
    void shouldHaveRequestMappingAnnotation() {
        assertTrue(CastleSiegeLordController.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestMapping.class),
                "Should have @RequestMapping annotation");
    }
}
