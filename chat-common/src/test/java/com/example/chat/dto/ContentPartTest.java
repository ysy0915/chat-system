package com.example.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentPartTest {

    @Test
    @DisplayName("静态工厂 text")
    void testTextFactory() {
        ContentPart p = ContentPart.text("hello world");
        assertEquals("text", p.getType());
        assertEquals("hello world", p.getText());
        assertNull(p.getImageUrl());
    }

    @Test
    @DisplayName("静态工厂 imageUrl")
    void testImageUrlFactory() {
        ContentPart p = ContentPart.imageUrl("http://example.com/img.png");
        assertEquals("image_url", p.getType());
        assertNotNull(p.getImageUrl());
        assertEquals("http://example.com/img.png", p.getImageUrl().getUrl());
    }

    @Test
    @DisplayName("toMap text 类型")
    void testToMapText() {
        ContentPart p = ContentPart.text("hello");
        Map<String, Object> map = p.toMap();
        assertEquals("text", map.get("type"));
        assertEquals("hello", map.get("text"));
    }

    @Test
    @DisplayName("toMap image_url 类型")
    void testToMapImageUrl() {
        ContentPart p = ContentPart.imageUrl("http://example.com/a.jpg");
        Map<String, Object> map = p.toMap();
        assertEquals("image_url", map.get("type"));
        assertNotNull(map.get("image_url"));
    }

    @Test
    @DisplayName("getter/setter")
    void testGetterSetter() {
        ContentPart p = new ContentPart();
        p.setType("text");
        p.setText("hello");

        ContentPart.ImageUrl iu = new ContentPart.ImageUrl("http://x.com");
        p.setImageUrl(iu);

        assertEquals("text", p.getType());
        assertEquals("hello", p.getText());
        assertEquals("http://x.com", p.getImageUrl().getUrl());
    }

    @Test
    @DisplayName("ImageUrl 内部类")
    void testImageUrl() {
        ContentPart.ImageUrl iu = new ContentPart.ImageUrl();
        iu.setUrl("http://a.com/pic.png");
        assertEquals("http://a.com/pic.png", iu.getUrl());

        Map<String, String> map = iu.toMap();
        assertEquals("http://a.com/pic.png", map.get("url"));
    }
}
