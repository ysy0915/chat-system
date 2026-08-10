package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceRecorderTest {

    @Test
    void shouldHaveServiceAnnotation() {
        assertTrue(
            TraceRecorder.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "TraceRecorder should have @Service annotation"
        );
    }

    @Test
    void shouldHaveRecordMethod() throws NoSuchMethodException {
        assertNotNull(TraceRecorder.class.getMethod("record", CallTrace.class));
    }

    @Test
    void shouldHaveGetRecentTracesMethod() throws NoSuchMethodException {
        assertNotNull(TraceRecorder.class.getMethod("getRecentTraces", int.class));
    }

    @Test
    void shouldHaveSearchTracesMethod() throws NoSuchMethodException {
        assertNotNull(TraceRecorder.class.getMethod("searchTraces", String.class));
    }
}
