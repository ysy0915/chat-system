package com.example.chat.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageRepositoryTest {

    @Test
    @DisplayName("接口类存在验证")
    void testInterfaceExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.repository.MessageRepository");
        });
    }

    @Test
    @DisplayName("是接口类型")
    void testIsInterface() {
        assertTrue(MessageRepository.class.isInterface());
    }

    @Test
    @DisplayName("有 Mapper 注解")
    void testHasMapperAnnotation() {
        assertNotNull(MessageRepository.class.getAnnotation(org.apache.ibatis.annotations.Mapper.class));
    }
}
