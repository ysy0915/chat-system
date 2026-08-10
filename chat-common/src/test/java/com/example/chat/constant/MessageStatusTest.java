package com.example.chat.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageStatusTest {

    @Test
    @DisplayName("常量值正确")
    void testConstants() {
        assertEquals("done", MessageStatus.DONE);
        assertEquals("error", MessageStatus.ERROR);
        assertEquals("running", MessageStatus.RUNNING);
        assertEquals("pending", MessageStatus.PENDING);
        assertEquals("stopped", MessageStatus.STOPPED);
        assertEquals("queued", MessageStatus.QUEUED);
    }
}
