package com.example.chat.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class MediaGenRecordTest {

    @Test
    @DisplayName("getter/setter 完整读写")
    void testGetterSetter() {
        MediaGenRecord r = new MediaGenRecord();
        Instant now = Instant.now();

        r.setId(1L);
        r.setUserId(100L);
        r.setPrompt("generate image");
        r.setMediaType("image");
        r.setModel("dall-e-3");
        r.setMediaUrl("http://example.com/img.png");
        r.setGlbUrl("http://example.com/model.glb");
        r.setObjUrl("http://example.com/model.obj");
        r.setPreviewUrl("http://example.com/preview.png");
        r.setStatus("done");
        r.setErrorMsg(null);
        r.setCreatedAt(now);

        assertEquals(1L, r.getId());
        assertEquals(100L, r.getUserId());
        assertEquals("generate image", r.getPrompt());
        assertEquals("image", r.getMediaType());
        assertEquals("dall-e-3", r.getModel());
        assertEquals("http://example.com/img.png", r.getMediaUrl());
        assertEquals("http://example.com/model.glb", r.getGlbUrl());
        assertEquals("http://example.com/model.obj", r.getObjUrl());
        assertEquals("http://example.com/preview.png", r.getPreviewUrl());
        assertEquals("done", r.getStatus());
        assertNull(r.getErrorMsg());
        assertEquals(now, r.getCreatedAt());
    }
}
