package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolFactoryTest {

    @Test
    @DisplayName("create 返回非空线程池")
    void testCreate() {
        var pool = ThreadPoolFactory.create(2, 4, 100, "test-");
        assertNotNull(pool);
        pool.shutdownNow();
    }
}
