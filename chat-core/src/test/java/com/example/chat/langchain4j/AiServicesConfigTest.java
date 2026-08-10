package com.example.chat.langchain4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiServicesConfigTest {

    @Test
    void shouldHaveConfigurationAnnotation() {
        assertTrue(AiServicesConfig.class.isAnnotationPresent(
                org.springframework.context.annotation.Configuration.class),
                "AiServicesConfig should be a @Configuration class");
    }
}
