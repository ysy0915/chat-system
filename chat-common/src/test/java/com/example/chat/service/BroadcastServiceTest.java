package com.example.chat.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BroadcastServiceTest {

    @Test
    @DisplayName("类存在性验证")
    void testClassExists() {
        assertNotNull(BroadcastService.class);
    }
}
