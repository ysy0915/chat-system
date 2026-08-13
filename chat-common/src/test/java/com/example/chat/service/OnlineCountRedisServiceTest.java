package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OnlineCountRedisServiceTest {

    @Test
    @DisplayName("类存在验证")
    void testClassExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.service.OnlineCountRedisService");
        });
    }

    @Test
    @DisplayName("方法验证 - recordSnapshot")
    void testHasRecordSnapshotMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("recordSnapshot", java.util.Map.class, java.time.LocalDateTime.class));
    }

    @Test
    @DisplayName("方法验证 - incrementVisitCount")
    void testHasIncrementVisitCountMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("incrementVisitCount", String.class, java.time.LocalDateTime.class));
    }

    @Test
    @DisplayName("方法验证 - getDailyVisitCounts")
    void testHasGetDailyVisitCountsMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("getDailyVisitCounts", java.time.LocalDateTime.class));
    }

    @Test
    @DisplayName("方法验证 - getPageDailyVisitCounts")
    void testHasGetPageDailyVisitCountsMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("getPageDailyVisitCounts", java.time.LocalDateTime.class));
    }

    @Test
    @DisplayName("方法验证 - getHourlyPeakTotal")
    void testHasGetHourlyPeakTotalMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("getHourlyPeakTotal"));
    }

    @Test
    @DisplayName("方法验证 - getHourlyActiveCount")
    void testHasGetHourlyActiveCountMethod() throws Exception {
        Class<?> clazz = Class.forName("com.example.chat.service.OnlineCountRedisService");
        assertNotNull(clazz.getDeclaredMethod("getHourlyActiveCount"));
    }
}
