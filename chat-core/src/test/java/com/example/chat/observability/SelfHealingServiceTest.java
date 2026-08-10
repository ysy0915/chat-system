package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfHealingServiceTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            SelfHealingService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "SelfHealingService should have @Service annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            SelfHealingService.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "SelfHealingService should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveHealAndRetryMethod() throws NoSuchMethodException {
        assertNotNull(SelfHealingService.class.getMethod("healAndRetry",
            com.example.chat.entity.ModelConfig.class,
            java.util.List.class,
            double.class,
            String.class,
            String.class,
            String.class,
            Exception.class));
    }
}
