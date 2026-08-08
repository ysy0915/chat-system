package com.example.chat.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心
 * Spring 启动时自动扫描所有 Tool 实现并注册
 * 受 app.agent.enabled=true 控制
 */
@Component
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * Spring 启动时自动注入所有 Tool 实现（容器中所有 Tool 类型的 bean）
     * 注意：Tool 实现类需放在 agent.tool.impl 包下，并标注 @Component 和
     * @ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
     */
    @Autowired(required = false)
    public void registerTools(List<Tool> toolBeans) {
        if (toolBeans == null || toolBeans.isEmpty()) {
            log.info("[ToolRegistry] 没有注册任何工具");
            return;
        }
        for (Tool tool : toolBeans) {
            Tool prev = tools.put(tool.getName(), tool);
            if (prev != null) {
                log.warn("[ToolRegistry] 工具名 '{}' 被覆盖: {} -> {}", tool.getName(),
                        prev.getClass().getSimpleName(), tool.getClass().getSimpleName());
            }
            log.info("[ToolRegistry] 注册工具: {} ({})", tool.getName(), tool.getClass().getSimpleName());
        }
        log.info("[ToolRegistry] 共注册 {} 个工具: {}", tools.size(), tools.keySet());
    }

    /** 按名获取工具 */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /** 获取所有工具 */
    public Collection<Tool> getAllTools() {
        return tools.values();
    }

    /** 是否注册了至少一个工具 */
    public boolean hasTools() {
        return !tools.isEmpty();
    }

    /**
     * 生成所有工具的 schema 列表，用于 OpenAI function calling 的 tools 参数
     * 格式：[{"type":"function","function":{"name":...,"description":...,"parameters":<json>}}]
     */
    public List<Map<String, Object>> getToolsSchema() {
        List<Map<String, Object>> schema = new ArrayList<>();
        for (Tool tool : tools.values()) {
            Object parametersJson;
            try {
                parametersJson = objectMapper.readValue(tool.getParameters(), Object.class);
            } catch (Exception e) {
                // parameters 不是合法 JSON，退化为空对象
                parametersJson = Map.of("type", "object", "properties", Map.of());
            }
            Map<String, Object> function = new java.util.LinkedHashMap<>();
            function.put("name", tool.getName());
            function.put("description", tool.getDescription());
            function.put("parameters", parametersJson);
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("type", "function");
            entry.put("function", function);
            schema.add(entry);
        }
        return schema;
    }
}
