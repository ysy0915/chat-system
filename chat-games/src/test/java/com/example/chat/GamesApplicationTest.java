package com.example.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 启动类存在性测试
 */
class GamesApplicationTest {

    @Test
    void shouldCreateApplicationInstance() {
        GamesApplication application = new GamesApplication();
        assertNotNull(application, "GamesApplication should be instantiable");
    }
}
