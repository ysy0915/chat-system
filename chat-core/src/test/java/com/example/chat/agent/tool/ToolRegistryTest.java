package com.example.chat.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(objectMapper);
    }

    @Test
    @DisplayName("构造函数成功")
    void testConstructor() {
        assertNotNull(registry);
    }

    @Test
    @DisplayName("初始状态无工具")
    void testInitiallyEmpty() {
        assertTrue(registry.getAllTools().isEmpty());
        assertFalse(registry.hasTools());
        assertNull(registry.getTool("nonexistent"));
    }

    @Test
    @DisplayName("注册工具后可获取")
    void testRegisterAndGet() {
        Tool tool = new TestTool("calc", "calculator", "calc something");
        registry.registerTools(List.of(tool));
        assertTrue(registry.hasTools());
        assertEquals(tool, registry.getTool("calc"));
        assertEquals(1, registry.getAllTools().size());
    }

    @Test
    @DisplayName("getToolsSchema 生成正确 schema")
    void testGetToolsSchema() {
        registry.registerTools(List.of(new TestTool("calc", "calculator", "calc desc")));
        var schema = registry.getToolsSchema();
        assertEquals(1, schema.size());
        assertEquals("function", schema.get(0).get("type"));
        @SuppressWarnings("unchecked")
        var func = (java.util.Map<String, Object>) schema.get(0).get("function");
        assertEquals("calc", func.get("name"));
    }

    @Test
    @DisplayName("registerTools null 不崩溃")
    void testRegisterNull() {
        assertDoesNotThrow(() -> registry.registerTools(null));
        assertFalse(registry.hasTools());
    }

    @Test
    @DisplayName("registerTools 空列表不崩溃")
    void testRegisterEmpty() {
        assertDoesNotThrow(() -> registry.registerTools(List.of()));
        assertFalse(registry.hasTools());
    }

    static class TestTool implements Tool {
        private final String name, desc, params;
        TestTool(String name, String desc, String params) {
            this.name = name; this.desc = desc; this.params = params;
        }
        @Override public String getName() { return name; }
        @Override public String getDescription() { return desc; }
        @Override public String getParameters() { return params; }
        @Override public String execute(java.util.Map<String, Object> args) { return "ok"; }
    }
}
