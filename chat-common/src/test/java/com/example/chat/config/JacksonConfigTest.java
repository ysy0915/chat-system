package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JacksonConfig 单元测试
 * 覆盖：Jackson2ObjectMapperBuilderCustomizer 注册 JavaTimeModule、禁用时间戳输出
 */
class JacksonConfigTest {

    private JacksonConfig config;

    @BeforeEach
    void setUp() {
        config = new JacksonConfig();
    }

    @Test
    @DisplayName("customizer 注册后 ObjectMapper 包含 JavaTimeModule")
    void customizer_registersJavaTimeModule() {
        Jackson2ObjectMapperBuilderCustomizer customizer = config.jacksonCustomizer();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer.customize(builder);

        ObjectMapper mapper = builder.build();
        assertNotNull(mapper);
        // JavaTimeModule 被注册，应该能序列化 java.time 类型
        assertTrue(mapper.getRegisteredModuleIds().stream()
                .anyMatch(id -> id instanceof String && ((String) id).contains("jackson-datatype-jsr310"))
                || mapper.getRegisteredModuleIds().stream()
                .anyMatch(id -> mapper.getSerializerFactory().toString().contains("JavaTime")));
    }

    @Test
    @DisplayName("customizer 禁用 WRITE_DATES_AS_TIMESTAMPS")
    void customizer_disablesTimestampWriting() {
        Jackson2ObjectMapperBuilderCustomizer customizer = config.jacksonCustomizer();
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        customizer.customize(builder);

        ObjectMapper mapper = builder.build();
        assertFalse(mapper.getSerializationConfig()
                .hasSerializationFeatures(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS.getMask()));
    }

    @Test
    @DisplayName("customizer 返回非空")
    void customizer_returnsNonNull() {
        assertNotNull(config.jacksonCustomizer());
    }
}
