package com.example.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring Boot 主启动类测试
 */
class WebApplicationTest {

    @Test
    void classExists() {
        assertNotNull(WebApplication.class);
    }

    @Test
    void mainMethodDeclared() throws NoSuchMethodException {
        // 验证 main 方法存在
        assertNotNull(WebApplication.class.getMethod("main", String[].class));
    }
}
