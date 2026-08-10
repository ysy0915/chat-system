package com.example.chat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GraphController 单元测试")
class GraphControllerTest {

    @Test
    @DisplayName("构造函数传 null 不抛出异常(无守卫)")
    void constructorAcceptsNullDependencies() {
        assertDoesNotThrow(() -> new GraphController(null));
    }

    @Test
    @DisplayName("构造函数正常创建实例")
    void constructorCreatesInstance() {
        GraphController controller = new GraphController(null);
        assertNotNull(controller);
    }
}
