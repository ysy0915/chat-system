package com.example.chat.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CallTraceTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        CallTrace ct = new CallTrace();
        ct.setTraceId("trace-001");
        ct.setScene("chat");
        ct.setProvider("openai");
        ct.setModel("gpt-4");
        ct.setStartTime(1000L);
        ct.setEndTime(2000L);
        ct.setLatency(1000L);
        ct.setStatus("SUCCESS");
        ct.setErrorMessage(null);
        ct.setToolCalls("calculator,weather");

        assertEquals("trace-001", ct.getTraceId());
        assertEquals("chat", ct.getScene());
        assertEquals("openai", ct.getProvider());
        assertEquals("gpt-4", ct.getModel());
        assertEquals(1000L, ct.getStartTime());
        assertEquals(2000L, ct.getEndTime());
        assertEquals(1000L, ct.getLatency());
        assertEquals("SUCCESS", ct.getStatus());
        assertNull(ct.getErrorMessage());
        assertEquals("calculator,weather", ct.getToolCalls());
    }

    @Test
    @DisplayName("全参构造函数")
    void testFullConstructor() {
        CallTrace ct = new CallTrace(
                "trace-001", "chat", "openai", "gpt-4",
                1000L, 2000L, 1000L,
                "SUCCESS", null, "calculator"
        );
        assertEquals("trace-001", ct.getTraceId());
        assertEquals("SUCCESS", ct.getStatus());
    }

    @Test
    @DisplayName("toJson")
    void testToJson() {
        CallTrace ct = new CallTrace("t1", "chat", "openai", "gpt-4",
                1000L, 2000L, 1000L, "SUCCESS", null, null);
        String json = ct.toJson();
        assertTrue(json.contains("t1"));
        assertTrue(json.contains("SUCCESS"));
    }
}
