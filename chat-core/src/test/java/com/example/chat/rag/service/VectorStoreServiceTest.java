package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorStoreServiceTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            VectorStoreService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "VectorStoreService should have @Service annotation"
        );
    }

    @Test
    void shouldHaveInsertChunksMethod() throws NoSuchMethodException {
        assertNotNull(VectorStoreService.class.getMethod("insertChunks",
            Long.class, Long.class, java.util.List.class, String.class));
    }

    @Test
    void shouldHaveSearchMethod() throws NoSuchMethodException {
        assertNotNull(VectorStoreService.class.getMethod("search", Long.class, String.class, int.class));
    }

    @Test
    void shouldHaveEnsureCollectionMethod() throws NoSuchMethodException {
        assertNotNull(VectorStoreService.class.getMethod("ensureCollection", Long.class));
    }

    @Test
    void shouldHaveDropCollectionMethod() throws NoSuchMethodException {
        assertNotNull(VectorStoreService.class.getMethod("dropCollection", Long.class));
    }
}
