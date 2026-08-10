package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        Message m = new Message();
        Instant now = Instant.now();

        m.setId(1L);
        m.setReqId("req-001");
        m.setUserId(100L);
        m.setQuestion("Hello?");
        m.setSummary("summary");
        m.setAnswerJson("{\"answer\":\"hi\"}");
        m.setStatus("done");
        m.setProvider("openai");
        m.setModel("gpt-4");
        m.setTokens(100);
        m.setIsPrivate(1);
        m.setCreatedAt(now);

        assertEquals(1L, m.getId());
        assertEquals("req-001", m.getReqId());
        assertEquals(100L, m.getUserId());
        assertEquals("Hello?", m.getQuestion());
        assertEquals("summary", m.getSummary());
        assertEquals("{\"answer\":\"hi\"}", m.getAnswerJson());
        assertEquals("done", m.getStatus());
        assertEquals("openai", m.getProvider());
        assertEquals("gpt-4", m.getModel());
        assertEquals(100, m.getTokens());
        assertEquals(1, m.getIsPrivate());
        assertEquals(now, m.getCreatedAt());
    }
}
