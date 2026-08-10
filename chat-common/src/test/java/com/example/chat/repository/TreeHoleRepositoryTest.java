package com.example.chat.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeHoleRepositoryTest {

    @Test
    @DisplayName("接口类存在验证")
    void testInterfaceExists() {
        assertDoesNotThrow(() -> {
            Class.forName("com.example.chat.repository.TreeHoleRepository");
        });
    }

    @Test
    @DisplayName("是接口类型")
    void testIsInterface() {
        assertTrue(TreeHoleRepository.class.isInterface());
    }

    @Test
    @DisplayName("有 Mapper 注解")
    void testHasMapperAnnotation() {
        assertNotNull(TreeHoleRepository.class.getAnnotation(org.apache.ibatis.annotations.Mapper.class));
    }
}
