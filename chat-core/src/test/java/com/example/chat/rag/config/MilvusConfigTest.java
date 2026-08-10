package com.example.chat.rag.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MilvusConfigTest {

    @Test
    void shouldHaveConfigurationAnnotation() {
        assertTrue(
            MilvusConfig.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class),
            "MilvusConfig should have @Configuration annotation"
        );
    }

    @Test
    void shouldHaveBeanMethods() throws NoSuchMethodException {
        assertNotNull(MilvusConfig.class.getDeclaredMethod("milvusServiceClient"));
    }
}
