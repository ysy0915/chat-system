package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        User u = new User();
        Instant now = Instant.now();

        u.setId(1L);
        u.setEmail("test@test.com");
        u.setPasswordHash("hash123");
        u.setName("TestUser");
        u.setNickname("Tester");
        u.setGuestName("Guest1");
        u.setRole("admin");
        u.setCreatedAt(now);

        assertEquals(1L, u.getId());
        assertEquals("test@test.com", u.getEmail());
        assertEquals("hash123", u.getPasswordHash());
        assertEquals("TestUser", u.getName());
        assertEquals("Tester", u.getNickname());
        assertEquals("Guest1", u.getGuestName());
        assertEquals("admin", u.getRole());
        assertEquals(now, u.getCreatedAt());
    }
}
