package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ModelConfigTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        ModelConfig c = new ModelConfig();
        Instant now = Instant.now();

        c.setId(1L);
        c.setProvider("openai");
        c.setModel("gpt-4");
        c.setApiKeyEncrypted("enc-key");
        c.setMetaJson("{\"baseUrl\":\"http://api.openai.com\"}");
        c.setPriority(50);
        c.setEnabled(false);
        c.setCreatedAt(now);
        c.setModelType("image");

        assertEquals(1L, c.getId());
        assertEquals("openai", c.getProvider());
        assertEquals("gpt-4", c.getModel());
        assertEquals("enc-key", c.getApiKeyEncrypted());
        assertEquals("{\"baseUrl\":\"http://api.openai.com\"}", c.getMetaJson());
        assertEquals(50, c.getPriority());
        assertFalse(c.getEnabled());
        assertEquals(now, c.getCreatedAt());
        assertEquals("image", c.getModelType());
    }
}
