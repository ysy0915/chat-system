package com.example.chat.rag.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeBaseTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("FAQ");
        kb.setDescription("常见问题");
        kb.setDocumentCount(5);
        kb.setTotalChunks(100L);
        kb.setCreatedAt("2024-01-01");

        assertEquals(1L, kb.getId());
        assertEquals("FAQ", kb.getName());
        assertEquals("常见问题", kb.getDescription());
        assertEquals(5, kb.getDocumentCount());
        assertEquals(100L, kb.getTotalChunks());
        assertEquals("2024-01-01", kb.getCreatedAt());
    }

    @Test
    @DisplayName("toString")
    void testToString() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setName("FAQ");
        assertTrue(kb.toString().contains("FAQ"));
    }
}
