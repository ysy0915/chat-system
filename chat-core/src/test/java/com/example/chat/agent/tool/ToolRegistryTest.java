package com.example.chat.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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

    @Test
    @DisplayName("DB 声明禁用工具后运行时不可见不可调用")
    void testDbDisableTool() {
        ToolRegistryRepository repo = Mockito.mock(ToolRegistryRepository.class);
        ToolDefinition dbDecl = new ToolDefinition("calc", "被禁用的计算器", "{\"type\":\"object\"}");
        dbDecl.setEnabled(false);
        Mockito.when(repo.findAll()).thenReturn(List.of(dbDecl));

        ToolRegistry registryWithDb = new ToolRegistry(objectMapper, repo);
        registryWithDb.registerTools(List.of(new TestTool("calc", "calculator", "{\"type\":\"object\"}")));

        assertFalse(registryWithDb.hasTools());
        assertNull(registryWithDb.getTool("calc"));
        assertTrue(registryWithDb.getToolsSchema().isEmpty());
        assertEquals(0, registryWithDb.getAllTools().size());
    }

    @Test
    @DisplayName("DB 声明覆盖描述与参数 Schema")
    void testDbOverrideMeta() {
        ToolRegistryRepository repo = Mockito.mock(ToolRegistryRepository.class);
        ToolDefinition dbDecl = new ToolDefinition("calc", "覆盖后的描述",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"number\"}}}");
        dbDecl.setEnabled(true);
        Mockito.when(repo.findAll()).thenReturn(List.of(dbDecl));

        ToolRegistry registryWithDb = new ToolRegistry(objectMapper, repo);
        registryWithDb.registerTools(List.of(new TestTool("calc", "原描述", "{\"type\":\"object\"}")));

        var schema = registryWithDb.getToolsSchema();
        assertEquals(1, schema.size());
        @SuppressWarnings("unchecked")
        var func = (java.util.Map<String, Object>) schema.get(0).get("function");
        assertEquals("覆盖后的描述", func.get("description"));
        assertEquals("calc", func.get("name"));
        @SuppressWarnings("unchecked")
        var params = (java.util.Map<String, Object>) func.get("parameters");
        assertTrue(params.containsKey("properties"));
    }

    @Test
    @DisplayName("DB 记录删除后恢复代码默认声明")
    void testDbDeleteRestoresCodeDefault() {
        ToolRegistryRepository repo = Mockito.mock(ToolRegistryRepository.class);
        ToolDefinition dbDecl = new ToolDefinition("calc", "被禁用的计算器", "{\"type\":\"object\"}");
        dbDecl.setEnabled(false);
        Mockito.when(repo.findAll()).thenReturn(List.of(dbDecl));

        ToolRegistry registryWithDb = new ToolRegistry(objectMapper, repo);
        registryWithDb.registerTools(List.of(new TestTool("calc", "calculator", "{\"type\":\"object\"}")));
        assertNull(registryWithDb.getTool("calc")); // DB 禁用生效

        // DB 记录删除后重新应用 → 恢复代码默认
        Mockito.when(repo.findAll()).thenReturn(List.of());
        registryWithDb.applyDbOverrides();
        assertNotNull(registryWithDb.getTool("calc"));
        assertEquals("calculator", registryWithDb.getTool("calc").getDescription());
    }

    @Test
    @DisplayName("DB 表未建时容错降级（不崩溃，按代码默认继续）")
    void testDbTableMissingDegrade() {
        ToolRegistryRepository repo = Mockito.mock(ToolRegistryRepository.class);
        Mockito.when(repo.findAll())
                .thenThrow(new RuntimeException("Table 'tool_registry' doesn't exist"));

        ToolRegistry registryWithDb = new ToolRegistry(objectMapper, repo);
        assertDoesNotThrow(() -> registryWithDb
                .registerTools(List.of(new TestTool("calc", "calculator", "{\"type\":\"object\"}"))));
        assertTrue(registryWithDb.hasTools());
        assertEquals("calculator", registryWithDb.getTool("calc").getDescription());
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
