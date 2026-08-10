package com.example.chat.rag.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RAGAutoConfigurationTest {

    @Test
    void shouldHaveConfigurationAnnotation() {
        assertTrue(
            RAGAutoConfiguration.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class),
            "RAGAutoConfiguration should have @Configuration annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            RAGAutoConfiguration.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "RAGAutoConfiguration should have @ConditionalOnProperty annotation"
        );
    }
}
