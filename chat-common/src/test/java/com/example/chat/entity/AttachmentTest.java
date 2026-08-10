package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        Attachment a = new Attachment();
        Instant now = Instant.now();

        a.setId(1L);
        a.setMessageId(100L);
        a.setUploadedBy(10L);
        a.setStorageUrl("/files/test.pdf");
        a.setMimeType("application/pdf");
        a.setFilename("test.pdf");
        a.setSize(1024L);
        a.setCreatedAt(now);

        assertEquals(1L, a.getId());
        assertEquals(100L, a.getMessageId());
        assertEquals(10L, a.getUploadedBy());
        assertEquals("/files/test.pdf", a.getStorageUrl());
        assertEquals("application/pdf", a.getMimeType());
        assertEquals("test.pdf", a.getFilename());
        assertEquals(1024L, a.getSize());
        assertEquals(now, a.getCreatedAt());
    }
}
