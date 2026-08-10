package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void shouldHaveSpringComponentAnnotation() {
        assertTrue(
            CircuitBreaker.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "CircuitBreaker should have @Component annotation"
        );
    }

    @Test
    void shouldHaveAllowRequestMethod() throws NoSuchMethodException {
        assertNotNull(CircuitBreaker.class.getMethod("allowRequest", String.class));
    }

    @Test
    void shouldHaveRecordSuccessMethod() throws NoSuchMethodException {
        assertNotNull(CircuitBreaker.class.getMethod("recordSuccess", String.class));
    }

    @Test
    void shouldHaveRecordFailureMethod() throws NoSuchMethodException {
        assertNotNull(CircuitBreaker.class.getMethod("recordFailure", String.class));
    }

    @Test
    void shouldHaveGetAllStatusMethod() throws NoSuchMethodException {
        assertNotNull(CircuitBreaker.class.getMethod("getAllStatus"));
    }
}
