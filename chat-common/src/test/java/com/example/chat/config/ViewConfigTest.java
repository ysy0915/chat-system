package com.example.chat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewConfigTest {

    @Test
    @DisplayName("类存在性验证")
    void testClassExists() {
        assertNotNull(ViewConfig.class);
    }
}
