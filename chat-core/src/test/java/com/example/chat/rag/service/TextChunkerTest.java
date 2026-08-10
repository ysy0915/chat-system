package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextChunkerTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            TextChunker.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "TextChunker should have @Service annotation"
        );
    }

    @Test
    void shouldHaveChunkMethod() throws NoSuchMethodException {
        assertNotNull(TextChunker.class.getMethod("chunk", String.class));
    }

    @Test
    void shouldHaveChunkWithSizeMethod() throws NoSuchMethodException {
        assertNotNull(TextChunker.class.getMethod("chunk", String.class, int.class, int.class));
    }
}
