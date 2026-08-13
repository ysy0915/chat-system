package com.example.chat.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamStopManagerTest {

    @Test
    void shouldRequestStop() {
        StreamStopManager manager = new StreamStopManager();
        manager.requestStop("req-1");
        assertTrue(manager.isStopped("req-1"));
    }

    @Test
    void shouldReturnFalseForUnknownReqId() {
        StreamStopManager manager = new StreamStopManager();
        assertFalse(manager.isStopped("unknown"));
    }

    @Test
    void shouldRemoveStopFlag() {
        StreamStopManager manager = new StreamStopManager();
        manager.requestStop("req-1");
        assertTrue(manager.isStopped("req-1"));
        manager.remove("req-1");
        assertFalse(manager.isStopped("req-1"));
    }

    @Test
    void shouldClearAllFlags() {
        StreamStopManager manager = new StreamStopManager();
        manager.requestStop("req-1");
        manager.requestStop("req-2");
        manager.clear();
        assertFalse(manager.isStopped("req-1"));
        assertFalse(manager.isStopped("req-2"));
    }
}
