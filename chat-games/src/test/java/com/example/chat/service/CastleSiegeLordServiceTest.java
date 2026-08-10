package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("CastleSiegeLordService 单元测试")
class CastleSiegeLordServiceTest {

    // ==================== 构造函数测试 ====================

    @Test
    @DisplayName("构造函数传 null 不抛出异常(无守卫)")
    void constructorAcceptsNullDependencies() {
        assertDoesNotThrow(() -> new CastleSiegeLordService(null));
    }

    @Test
    @DisplayName("构造函数正常创建实例")
    void constructorCreatesInstance() {
        CastleSiegeLordService service = new CastleSiegeLordService(null);
        assertNotNull(service);
    }

    // ==================== addLordScore 守卫条件测试 ====================

    @Test
    @DisplayName("addLordScore memberKey 为 null 时不抛异常提前返回")
    void addLordScoreReturnsEarlyWhenMemberKeyIsNull() {
        CastleSiegeLordService service = new CastleSiegeLordService(null);
        // memberKey=null 会触发提前返回，不会调用 redisTemplate
        assertDoesNotThrow(() -> service.addLordScore(null, "玩家A", 10, null));
    }

    @Test
    @DisplayName("addLordScore scoreDelta 为 0 时提前返回")
    void addLordScoreReturnsEarlyWhenScoreDeltaIsZero() {
        CastleSiegeLordService service = new CastleSiegeLordService(null);
        assertDoesNotThrow(() -> service.addLordScore("player1", "玩家A", 0, null));
    }

    @Test
    @DisplayName("addLordScore scoreDelta 为负数时提前返回")
    void addLordScoreReturnsEarlyWhenScoreDeltaIsNegative() {
        CastleSiegeLordService service = new CastleSiegeLordService(null);
        assertDoesNotThrow(() -> service.addLordScore("player1", "玩家A", -5, null));
    }
}
