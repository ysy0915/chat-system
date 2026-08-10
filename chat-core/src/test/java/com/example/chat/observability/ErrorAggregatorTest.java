package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErrorAggregatorTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            ErrorAggregator.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "ErrorAggregator should have @Service annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            ErrorAggregator.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "ErrorAggregator should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveRecordErrorMethod() throws NoSuchMethodException {
        assertNotNull(ErrorAggregator.class.getMethod("recordError",
            String.class, String.class, String.class, ErrorType.class, String.class));
    }

    @Test
    void shouldHaveGetErrorStatsMethod() throws NoSuchMethodException {
        assertNotNull(ErrorAggregator.class.getMethod("getErrorStats"));
    }

    @Test
    void shouldHaveGetTopErrorsMethod() throws NoSuchMethodException {
        assertNotNull(ErrorAggregator.class.getMethod("getTopErrors", int.class));
    }
}
