package com.example.chat.strategy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatStrategyTest {

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            OpenAICompatStrategy.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "OpenAICompatStrategy should have @Component annotation"
        );
    }

    @Test
    void shouldImplementLLMStrategy() {
        assertTrue(
            LLMStrategy.class.isAssignableFrom(OpenAICompatStrategy.class),
            "OpenAICompatStrategy should implement LLMStrategy"
        );
    }
}
