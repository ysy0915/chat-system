package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class OnlineCountRecordTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        OnlineCountRecord r = new OnlineCountRecord();
        LocalDateTime now = LocalDateTime.now();

        r.setId(1L);
        r.setPage("/chat");
        r.setCount(42);
        r.setRecordedAt(now);

        assertEquals(1L, r.getId());
        assertEquals("/chat", r.getPage());
        assertEquals(42, r.getCount());
        assertEquals(now, r.getRecordedAt());
    }
}
