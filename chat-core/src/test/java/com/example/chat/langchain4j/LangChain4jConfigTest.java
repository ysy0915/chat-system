package com.example.chat.langchain4j;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LangChain4jConfigTest {

    @Test
    void shouldHaveConfigurationAnnotation() {
        assertTrue(LangChain4jConfig.class.isAnnotationPresent(
                org.springframework.context.annotation.Configuration.class),
                "LangChain4jConfig should be a @Configuration class");
    }
}
