package com.example.chat.rag.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeControllerTest {

    @Test
    void shouldHaveRestControllerAnnotation() {
        assertTrue(
            KnowledgeController.class.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class),
            "KnowledgeController should have @RestController annotation"
        );
    }

    @Test
    void shouldHaveRequestMappingAnnotation() {
        assertTrue(
            KnowledgeController.class.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class),
            "KnowledgeController should have @RequestMapping annotation"
        );
    }

    @Test
    void shouldHaveCreateKnowledgeBaseMethod() throws NoSuchMethodException {
        assertNotNull(KnowledgeController.class.getMethod("createKnowledgeBase",
            java.util.Map.class, String.class));
    }

    @Test
    void shouldHaveListKnowledgeBasesMethod() throws NoSuchMethodException {
        assertNotNull(KnowledgeController.class.getMethod("listKnowledgeBases", String.class));
    }

    @Test
    void shouldHaveUploadDocumentMethod() throws NoSuchMethodException {
        assertNotNull(KnowledgeController.class.getMethod("uploadDocument",
            Long.class, org.springframework.web.multipart.MultipartFile.class, String.class));
    }
}
