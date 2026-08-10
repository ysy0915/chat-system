package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RAGUsageExampleTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            RAGUsageExample.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "RAGUsageExample should have @Service annotation"
        );
    }

    @Test
    void shouldHaveConditionalOnPropertyAnnotation() {
        assertTrue(
            RAGUsageExample.class.isAnnotationPresent(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class),
            "RAGUsageExample should have @ConditionalOnProperty annotation"
        );
    }

    @Test
    void shouldHaveTreeHoleAskWithRAGMethod() throws NoSuchMethodException {
        assertNotNull(RAGUsageExample.class.getMethod("treeHoleAskWithRAG",
            com.example.chat.entity.ModelConfig.class,
            String.class,
            java.util.List.class,
            String.class,
            String.class));
    }
}
