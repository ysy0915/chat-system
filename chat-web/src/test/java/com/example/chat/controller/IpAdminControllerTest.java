package com.example.chat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IpAdminController 单元测试")
class IpAdminControllerTest {

    @Test
    @DisplayName("构造函数传 null 不抛出异常(无守卫)")
    void constructorAcceptsNullDependencies() {
        assertDoesNotThrow(() -> new IpAdminController(null, null, null));
    }

    @Test
    @DisplayName("构造函数正常创建实例")
    void constructorCreatesInstance() {
        IpAdminController controller = new IpAdminController(null, null, null);
        assertNotNull(controller);
    }
}
