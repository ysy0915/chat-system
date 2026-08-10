package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            EmbeddingService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "EmbeddingService should have @Service annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            EmbeddingService.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "EmbeddingService should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveEmbedMethod() throws NoSuchMethodException {
        assertNotNull(EmbeddingService.class.getMethod("embed", String.class));
    }

    @Test
    void shouldHaveEmbedBatchMethod() throws NoSuchMethodException {
        assertNotNull(EmbeddingService.class.getMethod("embedBatch", java.util.List.class));
    }

    @Test
    void shouldHaveGetDimensionMethod() throws NoSuchMethodException {
        assertNotNull(EmbeddingService.class.getMethod("getDimension"));
    }
}
