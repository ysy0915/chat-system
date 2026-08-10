package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdleSessionCleanupTaskTest {

    @Test
    @DisplayName("构造函数传 null 也不抛异常")
    void testConstructorNull() {
        // cleanup 有 try-catch 兜底，构造传 null 不会立即 NPE
        IdleSessionCleanupTask task = new IdleSessionCleanupTask(null);
        assertNotNull(task);
    }
}
