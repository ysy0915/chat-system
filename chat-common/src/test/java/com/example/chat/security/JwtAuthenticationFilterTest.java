package com.example.chat.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthenticationFilterTest {

    @Test
    @DisplayName("类存在性验证")
    void testClassExists() {
        assertNotNull(JwtAuthenticationFilter.class);
    }
}
