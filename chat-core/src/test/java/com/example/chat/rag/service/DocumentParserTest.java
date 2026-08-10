package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            DocumentParser.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "DocumentParser should have @Service annotation"
        );
    }

    @Test
    void shouldHaveParseMethod() throws NoSuchMethodException {
        assertNotNull(DocumentParser.class.getMethod("parse", String.class, byte[].class));
    }
}
