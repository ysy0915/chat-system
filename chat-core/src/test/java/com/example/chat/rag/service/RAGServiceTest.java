package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RAGServiceTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            RAGService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "RAGService should have @Service annotation"
        );
    }

    @Test
    void shouldHaveInvokeWithRAGMethod() throws NoSuchMethodException {
        assertNotNull(RAGService.class.getMethod("invokeWithRAG",
            com.example.chat.entity.ModelConfig.class, Long.class,
            String.class, java.util.List.class,
            double.class, String.class,
            String.class, String.class));
    }

    @Test
    void shouldHaveRetrieveContextMethod() throws NoSuchMethodException {
        assertNotNull(RAGService.class.getMethod("retrieveContext", Long.class, String.class));
    }

    @Test
    void shouldHaveInvokeWithRAGStreamMethod() throws NoSuchMethodException {
        assertNotNull(RAGService.class.getMethod("invokeWithRAGStream",
            com.example.chat.entity.ModelConfig.class, Long.class,
            String.class, java.util.List.class,
            double.class, String.class,
            String.class, String.class,
            java.util.function.Consumer.class));
    }
}
