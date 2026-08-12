package com.example.chat.controller;

import com.example.chat.dto.MediaGenerateRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaGenController 类存在性和结构验证测试。
 * Mockito 在 Java 26 下存在兼容性问题，故采用反射验证。
 */
class MediaGenControllerTest {

    @Test
    void shouldHaveClass() {
        Class<?> clazz = MediaGenController.class;
        assertNotNull(clazz, "MediaGenController should exist");
    }

    @Test
    void shouldHaveGenerateMethod() throws Exception {
        Method method = MediaGenController.class.getDeclaredMethod("generate", MediaGenerateRequest.class);
        assertNotNull(method, "generate method should exist");
    }

    @Test
    void shouldHaveGetStatusMethod() throws Exception {
        Method method = MediaGenController.class.getDeclaredMethod("getStatus", Long.class);
        assertNotNull(method, "getStatus method should exist");
    }

    @Test
    void shouldHaveGetHistoryMethod() throws Exception {
        Method method = MediaGenController.class.getDeclaredMethod("getHistory", String.class, int.class);
        assertNotNull(method, "getHistory method should exist");
    }

    @Test
    void shouldHaveCheck3DAccessMethod() throws Exception {
        Method method = MediaGenController.class.getDeclaredMethod("check3DAccess");
        assertNotNull(method, "check3DAccess method should exist");
    }

    @Test
    void shouldHaveRestControllerAnnotation() {
        assertTrue(MediaGenController.class.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class),
                "MediaGenController should have @RestController annotation");
    }

    @Test
    void shouldHaveRequestMappingAnnotation() {
        assertTrue(MediaGenController.class.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class),
                "MediaGenController should have @RequestMapping annotation");
    }
}
