package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DebateRecordTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        DebateRecord r = new DebateRecord();
        Instant now = Instant.now();

        r.setId(1L);
        r.setUserId(100L);
        r.setUserName("Alice");
        r.setQuestion("What is AI?");
        r.setFinalAnswer("AI is ...");
        r.setStatus("done");
        r.setCreatedAt(now);
        r.setUpdatedAt(now);

        assertEquals(1L, r.getId());
        assertEquals(100L, r.getUserId());
        assertEquals("Alice", r.getUserName());
        assertEquals("What is AI?", r.getQuestion());
        assertEquals("AI is ...", r.getFinalAnswer());
        assertEquals("done", r.getStatus());
        assertEquals(now, r.getCreatedAt());
        assertEquals(now, r.getUpdatedAt());
    }
}
