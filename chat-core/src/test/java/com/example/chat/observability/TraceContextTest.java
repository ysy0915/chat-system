package com.example.chat.observability;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceContextTest {

    @Test
    void shouldHaveComponentAnnotation() {
        assertTrue(
            TraceContext.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
            "TraceContext should have @Component annotation"
        );
    }

    @Test
    void shouldHaveStartMethod() throws NoSuchMethodException {
        assertNotNull(TraceContext.class.getMethod("start"));
    }

    @Test
    void shouldHaveGetMethod() throws NoSuchMethodException {
        assertNotNull(TraceContext.class.getMethod("get"));
    }

    @Test
    void shouldHaveClearMethod() throws NoSuchMethodException {
        assertNotNull(TraceContext.class.getMethod("clear"));
    }
}
