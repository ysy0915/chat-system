package com.example.chat.rag.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeDocumentTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(1L);
        doc.setKnowledgeBaseId(10L);
        doc.setFileName("readme.txt");
        doc.setSource("upload");
        doc.setChunkCount(3);
        doc.setFileSize(2048L);
        doc.setStatus("done");
        doc.setErrorMessage(null);
        doc.setCreatedAt("2024-01-01");

        assertEquals(1L, doc.getId());
        assertEquals(10L, doc.getKnowledgeBaseId());
        assertEquals("readme.txt", doc.getFileName());
        assertEquals("upload", doc.getSource());
        assertEquals(3, doc.getChunkCount());
        assertEquals(2048L, doc.getFileSize());
        assertEquals("done", doc.getStatus());
        assertNull(doc.getErrorMessage());
        assertEquals("2024-01-01", doc.getCreatedAt());
    }

    @Test
    @DisplayName("toString")
    void testToString() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(1L);
        doc.setFileName("test.txt");
        assertTrue(doc.toString().contains("test.txt"));
    }
}
