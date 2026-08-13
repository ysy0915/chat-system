package com.example.chat.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmConfigPropertiesTest {

    @Test
    void shouldHaveDefaultValues() {
        LlmConfigProperties props = new LlmConfigProperties();
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", props.getBaseUrl());
        assertEquals("", props.getApiKey());
        assertEquals("qwen-plus", props.getModel());
        assertEquals("qwen", props.getProvider());
    }

    @Test
    void shouldSetAndGetBaseUrl() {
        LlmConfigProperties props = new LlmConfigProperties();
        props.setBaseUrl("https://custom.api.com/v1");
        assertEquals("https://custom.api.com/v1", props.getBaseUrl());
    }

    @Test
    void shouldSetAndGetApiKey() {
        LlmConfigProperties props = new LlmConfigProperties();
        props.setApiKey("sk-test-key");
        assertEquals("sk-test-key", props.getApiKey());
    }

    @Test
    void shouldSetAndGetModel() {
        LlmConfigProperties props = new LlmConfigProperties();
        props.setModel("gpt-4");
        assertEquals("gpt-4", props.getModel());
    }

    @Test
    void shouldSetAndGetProvider() {
        LlmConfigProperties props = new LlmConfigProperties();
        props.setProvider("openai");
        assertEquals("openai", props.getProvider());
    }
}
