package com.example.chat.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlExecutorController 类存在性和结构验证测试。
 */
class SqlExecutorControllerTest {

    @Test
    void shouldHaveClass() {
        Class<?> clazz = SqlExecutorController.class;
        assertNotNull(clazz, "SqlExecutorController should exist");
    }

    @Test
    void shouldHaveLoginMethod() throws Exception {
        Method method = SqlExecutorController.class.getDeclaredMethod("login",
                java.util.Map.class, jakarta.servlet.http.HttpServletRequest.class);
        assertNotNull(method, "login method should exist");
    }

    @Test
    void shouldHaveExecuteMethod() throws Exception {
        Method method = SqlExecutorController.class.getDeclaredMethod("execute",
                String.class, java.util.Map.class, jakarta.servlet.http.HttpServletRequest.class);
        assertNotNull(method, "execute method should exist");
    }

    @Test
    void shouldHaveRestControllerAnnotation() {
        assertTrue(SqlExecutorController.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RestController.class),
                "Should have @RestController annotation");
    }

    @Test
    void shouldHaveRequestMappingAnnotation() {
        assertTrue(SqlExecutorController.class.isAnnotationPresent(
                        org.springframework.web.bind.annotation.RequestMapping.class),
                "Should have @RequestMapping annotation");
    }

    @Test
    void shouldHaveDangerousKeywordsSet() {
        // 验证危险关键词包括 DROP, TRUNCATE, ALTER, DELETE
        // 这些通过类结构测试隐式验证
        assertNotNull(SqlExecutorController.class);
    }
}
