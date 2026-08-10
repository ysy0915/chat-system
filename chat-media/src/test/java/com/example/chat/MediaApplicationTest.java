package com.example.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 启动类存在性测试
 */
class MediaApplicationTest {

    @Test
    void shouldCreateApplicationInstance() {
        MediaApplication application = new MediaApplication();
        assertNotNull(application, "MediaApplication should be instantiable");
    }
}
