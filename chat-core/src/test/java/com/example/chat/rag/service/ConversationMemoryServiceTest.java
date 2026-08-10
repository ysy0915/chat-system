package com.example.chat.rag.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryServiceTest {

    @Test
    void shouldHaveSpringServiceAnnotation() {
        assertTrue(
            ConversationMemoryService.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "ConversationMemoryService should have @Service annotation"
        );
    }

    @Test
    void shouldHaveSaveConversationMethod() throws NoSuchMethodException {
        assertNotNull(ConversationMemoryService.class.getMethod("saveConversation",
            String.class, Long.class, String.class, String.class));
    }

    @Test
    void shouldHaveBuildMemoryContextMethod() throws NoSuchMethodException {
        assertNotNull(ConversationMemoryService.class.getMethod("buildMemoryContext",
            String.class, Long.class, String.class));
    }

    @Test
    void shouldHaveClearShortTermMethod() throws NoSuchMethodException {
        assertNotNull(ConversationMemoryService.class.getMethod("clearShortTerm",
            String.class, Long.class));
    }
}
