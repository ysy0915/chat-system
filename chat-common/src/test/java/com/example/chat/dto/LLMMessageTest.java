package com.example.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LLMMessageTest {

    @Test
    @DisplayName("静态工厂 system/user/assistant")
    void testStaticFactories() {
        assertEquals("system", LLMMessage.system("sys").getRole());
        assertEquals("user", LLMMessage.user("hi").getRole());
        assertEquals("assistant", LLMMessage.assistant("hello").getRole());
    }

    @Test
    @DisplayName("getTextContent 文本消息")
    void testGetTextContent() {
        LLMMessage m = LLMMessage.user("hello");
        assertEquals("hello", m.getTextContent());
        assertFalse(m.isMultimodal());
    }

    @Test
    @DisplayName("userWithImage 多模态")
    void testUserWithImage() {
        LLMMessage m = LLMMessage.userWithImage("describe", "abc123", "image/png");
        assertEquals("user", m.getRole());
        assertTrue(m.isMultimodal());
        assertNull(m.getTextContent());
    }

    @Test
    @DisplayName("toMap 文本消息")
    void testToMapText() {
        LLMMessage m = LLMMessage.user("hello");
        Map<String, Object> map = m.toMap();
        assertEquals("user", map.get("role"));
        assertEquals("hello", map.get("content"));
    }

    @Test
    @DisplayName("toMapList 批量转换")
    void testToMapList() {
        List<Map<String, Object>> list = LLMMessage.toMapList(List.of(
                LLMMessage.system("sys"),
                LLMMessage.user("hi")
        ));
        assertEquals(2, list.size());
        assertEquals("system", list.get(0).get("role"));
    }

    @Test
    @DisplayName("fromMap 转换")
    void testFromMap() {
        Map<String, Object> map = Map.of("role", "user", "content", "hello");
        LLMMessage m = LLMMessage.fromMap(map);
        assertEquals("user", m.getRole());
        assertEquals("hello", m.getContent());
    }

    @Test
    @DisplayName("getter/setter")
    void testGetterSetter() {
        LLMMessage m = new LLMMessage();
        m.setRole("user");
        m.setContent("text");
        m.setName("func");
        assertEquals("user", m.getRole());
        assertEquals("text", m.getContent());
        assertEquals("func", m.getName());
    }

    @Test
    @DisplayName("toString 截断")
    void testToString() {
        LLMMessage m = LLMMessage.user("a".repeat(100));
        String s = m.toString();
        assertTrue(s.contains("LLMMessage"));
        assertTrue(s.contains("role='user'"));
    }
}
