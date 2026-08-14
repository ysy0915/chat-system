package com.example.chat.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>工具注册中心（工具平台化）</h2>
 *
 * <p>[B档] 工具平台化：在 Spring 自动收集所有 {@link Tool} 实现的基础上，
 * 为每个工具维护一份可被 <b>DB 注册表（tool_registry）覆盖</b> 的元数据定义：
 * <ul>
 *   <li>代码工具启动注册（source=CODE，取实现类声明的 name/description/parameters）</li>
 *   <li>{@link #applyDbOverrides()} 合并 DB 声明：可禁用工具、替换描述/参数 Schema、限定范围</li>
 *   <li>运行时（schema 生成 / 执行分发）仅暴露 enabled 工具，禁用工具对 LLM 不可见不可调用</li>
 * </ul>
 * 管理面 API 见 {@code ToolAdminController}（/internal/tools）。</p>
 *
 * <p>受 <code>app.agent.enabled=true</code> 控制。</p>
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final ObjectMapper objectMapper;

    /** 工具名 → 工具执行器（注册中心运行时字典） */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** 工具名 → 元数据定义（CODE 默认 + DB 覆盖合并后的最终视图） */
    private final Map<String, ToolDefinition> definitions = new ConcurrentHashMap<>();

    /** DB 注册表仓储（可空：未配置 DB / 测试环境时跳过覆盖） */
    private final ToolRegistryRepository repository;

    /**
     * 便捷构造：无 DB 注册表（单元测试 / 非 Spring 环境）。
     */
    public ToolRegistry(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    /**
     * Spring 构造器：自动收集所有 {@link Tool} Bean；存在 DB 注册表时应用覆盖声明。
     */
    @Autowired(required = false)
    public ToolRegistry(ObjectMapper objectMapper, ToolRegistryRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    /**
     * Spring 启动时自动注入所有 Tool 实现（容器中所有 Tool 类型的 bean）。
     * 注册完成后立即应用 DB 覆盖声明。
     */
    @Autowired(required = false)
    public void registerTools(List<Tool> toolBeans) {
        if (toolBeans == null || toolBeans.isEmpty()) {
            log.info("[ToolRegistry] 没有注册任何工具");
            return;
        }
        for (Tool tool : toolBeans) {
            Tool prev = tools.put(tool.getName(), tool);
            definitions.put(tool.getName(), ToolDefinition.fromCode(tool));
            if (prev != null) {
                log.warn("[ToolRegistry] 工具名 '{}' 被覆盖: {} -> {}", tool.getName(),
                        prev.getClass().getSimpleName(), tool.getClass().getSimpleName());
            }
            log.info("[ToolRegistry] 注册工具: {} ({})", tool.getName(), tool.getClass().getSimpleName());
        }
        applyDbOverrides();
        log.info("[ToolRegistry] 共注册 {} 个工具，启用 {} 个: {}",
                definitions.size(),
                definitions.values().stream().filter(ToolDefinition::isEnabled).count(),
                definitions.keySet());
    }

    /**
     * 应用 DB 注册表声明（tool_registry 表）覆盖代码默认定义：
     * <ul>
     *   <li>DB enabled=0 → 禁用工具（运行时不可见不可调用）</li>
     *   <li>DB description / parameters / scope 非空 → 覆盖代码声明</li>
     * </ul>
     * 表未建 / DB 不可用时容错降级（按代码默认注册继续），不阻塞启动。
     */
    @SuppressWarnings("PMD.NPathComplexity") // DB 覆盖声明逐字段合并，分支语义直白，拆分无收益
    public void applyDbOverrides() {
        if (repository == null) {
            return;
        }
        try {
            // 1) 重置为代码默认声明（DB 记录被删除后自动恢复代码默认）
            for (Map.Entry<String, Tool> e : tools.entrySet()) {
                definitions.put(e.getKey(), ToolDefinition.fromCode(e.getValue()));
            }
            // 2) 合并 DB 覆盖声明
            List<ToolDefinition> rows = repository.findAll();
            if (rows == null || rows.isEmpty()) {
                log.info("[ToolRegistry] DB 声明为空，全部恢复代码默认；当前启用 {}/{} 个工具",
                        definitions.values().stream().filter(ToolDefinition::isEnabled).count(),
                        definitions.size());
                return;
            }
            for (ToolDefinition row : rows) {
                ToolDefinition def = definitions.get(row.getToolName());
                if (def == null) {
                    log.info("[ToolRegistry] DB 声明工具 '{}' 无代码实现，忽略", row.getToolName());
                    continue;
                }
                if (row.getDescription() != null && !row.getDescription().isBlank()) {
                    def.setDescription(row.getDescription());
                }
                if (row.getParameters() != null && !row.getParameters().isBlank()) {
                    def.setParameters(row.getParameters());
                }
                if (row.getScope() != null && !row.getScope().isBlank()) {
                    def.setScope(row.getScope());
                }
                def.setEnabled(row.isEnabled());
                def.setSource(ToolDefinition.SOURCE_DB);
            }
            log.info("[ToolRegistry] DB 声明应用完成，{} 条覆盖；当前启用 {}/{} 个工具",
                    rows.size(),
                    definitions.values().stream().filter(ToolDefinition::isEnabled).count(),
                    definitions.size());
        } catch (Exception e) {
            log.warn("[ToolRegistry] DB 声明加载失败（tool_registry 表未建？），按代码默认注册继续: {}",
                    e.getMessage());
        }
    }

    /** 按名获取工具执行器；DB 禁用的工具返回 null（不可调用） */
    public Tool getTool(String name) {
        ToolDefinition def = definitions.get(name);
        if (def != null && !def.isEnabled()) {
            return null;
        }
        return tools.get(name);
    }

    /** 获取所有启用的工具执行器 */
    public Collection<Tool> getAllTools() {
        List<Tool> list = new ArrayList<>();
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            if (isEnabled(e.getKey())) {
                list.add(e.getValue());
            }
        }
        return list;
    }

    /** 是否注册了至少一个启用的工具 */
    public boolean hasTools() {
        return definitions.values().stream().anyMatch(ToolDefinition::isEnabled);
    }

    /** 工具是否启用 */
    public boolean isEnabled(String name) {
        ToolDefinition def = definitions.get(name);
        return def == null || def.isEnabled();
    }

    /**
     * 工具元数据视图（管理面展示，按名称排序）。
     */
    public List<ToolDefinition> listDefinitions() {
        List<ToolDefinition> list = new ArrayList<>(definitions.values());
        list.sort(Comparator.comparing(ToolDefinition::getToolName));
        return list;
    }

    /**
     * 生成所有启用的工具 schema，用于 OpenAI function calling 的 tools 参数。
     * 格式：[{"type":"function","function":{"name":...,"description":...,"parameters":<json>}}]
     */
    public List<Map<String, Object>> getToolsSchema() {
        List<Map<String, Object>> schema = new ArrayList<>();
        for (Map.Entry<String, ToolDefinition> e : definitions.entrySet()) {
            if (!e.getValue().isEnabled()) {
                continue;
            }
            Tool tool = tools.get(e.getKey());
            if (tool != null) {
                schema.add(buildSchemaEntry(e.getValue()));
            }
        }
        return schema;
    }

    /**
     * 按工具名范围生成 schema（Sub-Agent Worker 限定 tools_scope 用）。
     * 范围为空或全不匹配时返回空列表（表示无可用工具）。
     */
    public List<Map<String, Object>> getToolsSchema(List<String> names) {
        List<Map<String, Object>> schema = new ArrayList<>();
        if (names == null || names.isEmpty()) return schema;
        for (String name : names) {
            ToolDefinition def = definitions.get(name);
            if (def == null || !def.isEnabled()) {
                continue;
            }
            Tool tool = tools.get(name);
            if (tool != null) {
                schema.add(buildSchemaEntry(def));
            }
        }
        return schema;
    }

    /** 单条工具的 OpenAI function schema（描述/参数以定义视图为准，可被 DB 覆盖） */
    private Map<String, Object> buildSchemaEntry(ToolDefinition def) {
        Object parametersJson;
        try {
            parametersJson = objectMapper.readValue(def.getParameters(), Object.class);
        } catch (Exception e) {
            // parameters 不是合法 JSON，退化为空对象
            parametersJson = Map.of("type", "object", "properties", Map.of());
        }
        Map<String, Object> function = new java.util.LinkedHashMap<>();
        function.put("name", def.getToolName());
        function.put("description", def.getDescription());
        function.put("parameters", parametersJson);
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("type", "function");
        entry.put("function", function);
        return entry;
    }
}
