package com.example.chat.agent.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolDispatcherTest {

    @Test
    void shouldHaveServiceAnnotation() {
        assertTrue(
            ToolDispatcher.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "ToolDispatcher should have @Service annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            ToolDispatcher.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "ToolDispatcher should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveDispatchMethod() throws NoSuchMethodException {
        assertNotNull(ToolDispatcher.class.getMethod("dispatch",
            String.class,
            com.example.chat.entity.ModelConfig.class,
            java.util.List.class,
            double.class,
            String.class,
            String.class,
            String.class));
    }
}
