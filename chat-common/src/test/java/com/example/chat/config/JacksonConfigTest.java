package com.example.chat.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;

import static org.junit.jupiter.api.Assertions.*;

class JacksonConfigTest {

    @Test
    @DisplayName("jacksonCustomizer bean 方法")
    void testJacksonCustomizer() {
        JacksonConfig config = new JacksonConfig();
        Jackson2ObjectMapperBuilderCustomizer customizer = config.jacksonCustomizer();
        assertNotNull(customizer);
    }
}
