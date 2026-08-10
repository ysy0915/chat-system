package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CastleSiegeBattlefieldService 单元测试")
class CastleSiegeBattlefieldServiceTest {

    @Test
    @DisplayName("构造函数传 null 不抛出异常(无守卫)")
    void constructorAcceptsNullDependencies() {
        assertDoesNotThrow(() -> new CastleSiegeBattlefieldService(null, null, null));
    }

    @Test
    @DisplayName("构造函数正常创建实例")
    void constructorCreatesInstance() {
        CastleSiegeBattlefieldService service = new CastleSiegeBattlefieldService(null, null, null);
        assertNotNull(service);
    }
}
