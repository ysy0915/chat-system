package com.example.chat.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DoubaoStrategyTest {

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            DoubaoStrategy.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "DoubaoStrategy should have @Component annotation"
        );
    }

    @Test
    void shouldImplementLLMStrategy() {
        assertTrue(
            LLMStrategy.class.isAssignableFrom(DoubaoStrategy.class),
            "DoubaoStrategy should implement LLMStrategy"
        );
    }
}
