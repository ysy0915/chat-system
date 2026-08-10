package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TreeHoleMessageTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        TreeHoleMessage m = new TreeHoleMessage();
        Instant now = Instant.now();

        m.setId(1L);
        m.setReqId("req-001");
        m.setUserId(100L);
        m.setQuestion("secret");
        m.setAnswerJson("{\"reply\":\"ok\"}");
        m.setStatus("done");
        m.setMood("happy");
        m.setProvider("openai");
        m.setModel("gpt-4");
        m.setTokens(200);
        m.setCreatedAt(now);

        assertEquals(1L, m.getId());
        assertEquals("req-001", m.getReqId());
        assertEquals(100L, m.getUserId());
        assertEquals("secret", m.getQuestion());
        assertEquals("{\"reply\":\"ok\"}", m.getAnswerJson());
        assertEquals("done", m.getStatus());
        assertEquals("happy", m.getMood());
        assertEquals("openai", m.getProvider());
        assertEquals("gpt-4", m.getModel());
        assertEquals(200, m.getTokens());
        assertEquals(now, m.getCreatedAt());
    }
}
