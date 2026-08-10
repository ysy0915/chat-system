package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RestTemplateConfig 类存在验证测试
 */
class RestTemplateConfigTest {

    @Test
    void classExists() {
        RestTemplateConfig config = new RestTemplateConfig();
        assertNotNull(config);
    }

    @Test
    void restTemplateBeanCreated() {
        RestTemplateConfig config = new RestTemplateConfig();
        RestTemplate restTemplate = config.restTemplate(new ObjectMapper());
        assertNotNull(restTemplate);
    }
}
