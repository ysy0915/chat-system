package com.example.chat.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LLMStrategyTest {

    @Test
    void shouldBeInterface() {
        assertTrue(LLMStrategy.class.isInterface(), "LLMStrategy should be an interface");
    }
}
