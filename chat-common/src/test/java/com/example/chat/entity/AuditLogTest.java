package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        AuditLog log = new AuditLog();
        LocalDateTime now = LocalDateTime.now();

        log.setId(1L);
        log.setEventType("LOGIN");
        log.setUserId("100");
        log.setUsername("admin");
        log.setIpAddress("127.0.0.1");
        log.setUserAgent("Mozilla/5.0");
        log.setDetail("login success");
        log.setResult("SUCCESS");
        log.setCreatedAt(now);

        assertEquals(1L, log.getId());
        assertEquals("LOGIN", log.getEventType());
        assertEquals("100", log.getUserId());
        assertEquals("admin", log.getUsername());
        assertEquals("127.0.0.1", log.getIpAddress());
        assertEquals("Mozilla/5.0", log.getUserAgent());
        assertEquals("login success", log.getDetail());
        assertEquals("SUCCESS", log.getResult());
        assertEquals(now, log.getCreatedAt());
    }
}
