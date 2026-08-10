package com.example.chat.router;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {

    @Test
    void shouldHaveServiceAnnotation() {
        assertTrue(
            ModelRouter.class.isAnnotationPresent(org.springframework.stereotype.Service.class),
            "ModelRouter should have @Service annotation"
        );
    }

    @Test
    void shouldHaveRouteMethod() throws NoSuchMethodException {
        assertNotNull(ModelRouter.class.getMethod("route", TaskType.class, String.class, Long.class));
    }
}
