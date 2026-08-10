package com.example.chat.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryTest {

    @Test
    @DisplayName("接口类存在验证")
    void testInterfaceExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.repository.UserRepository");
        });
    }

    @Test
    @DisplayName("是接口类型")
    void testIsInterface() {
        assertTrue(UserRepository.class.isInterface());
    }

    @Test
    @DisplayName("有 Mapper 注解")
    void testHasMapperAnnotation() {
        assertNotNull(UserRepository.class.getAnnotation(org.apache.ibatis.annotations.Mapper.class));
    }
}
