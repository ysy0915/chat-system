package com.example.chat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreApplicationTest {

    @Test
    void shouldHaveMainMethod() throws NoSuchMethodException {
        assertNotNull(CoreApplication.class.getMethod("main", String[].class));
    }

    @Test
    void shouldHaveSpringBootApplicationAnnotation() {
        assertTrue(
            CoreApplication.class.isAnnotationPresent(org.springframework.boot.autoconfigure.SpringBootApplication.class),
            "CoreApplication should have @SpringBootApplication annotation"
        );
    }
}
