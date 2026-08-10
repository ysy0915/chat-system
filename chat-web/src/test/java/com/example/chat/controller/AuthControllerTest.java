package com.example.chat.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuthController 单元测试")
@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private PasswordEncoder passwordEncoder;

    // ==================== 构造函数测试 ====================

    @Test
    @DisplayName("构造函数传 null 不抛出异常(无守卫)")
    void constructorAcceptsNullDependencies() {
        assertDoesNotThrow(() -> new AuthController(null, null, null));
    }

    @Test
    @DisplayName("构造函数正常创建实例")
    void constructorCreatesInstance() {
        AuthController controller = new AuthController(null, passwordEncoder, null);
        assertNotNull(controller);
    }
}
