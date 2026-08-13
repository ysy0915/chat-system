package com.example.chat.internal;

import com.example.chat.agent.tool.ToolDefinition;
import com.example.chat.agent.tool.ToolRegistry;
import com.example.chat.agent.tool.ToolRegistryRepository;
import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <h2>工具平台化管理面 API（/internal/tools）</h2>
 *
 * <p>[B档] 工具平台化：工具的<b>元数据声明</b>（启用/描述/参数 Schema/范围）落库
 * （tool_registry 表）并即时生效，无需发版即可开关工具 / 调整 AI 可见的提示词。</p>
 *
 * <p>仅供 chat-web（鉴权后）或运维内部调用；执行逻辑仍由代码实现，DB 仅控制声明视图。</p>
 */
@Tag(name = "工具管理", description = "工具平台化管理面（元数据声明 + 注册表）")
@RestController
@RequestMapping("/internal/tools")
public class ToolAdminController {

    private static final Logger log = LoggerFactory.getLogger(ToolAdminController.class);

    private final ToolRegistry registry;
    private final ToolRegistryRepository repository;

    public ToolAdminController(ToolRegistry registry, ToolRegistryRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Operation(summary = "工具定义列表（含启用/范围/来源）")
    @GetMapping
    public Object list() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", registry.listDefinitions().size());
        result.put("enabled", registry.listDefinitions().stream().filter(ToolDefinition::isEnabled).count());
        result.put("tools", registry.listDefinitions());
        return ApiResponse.ok(result);
    }

    @Operation(summary = "更新工具声明（启用/描述/参数 Schema/范围，落库即时生效）")
    @PutMapping("/{name}")
    public Object update(@PathVariable String name, @RequestBody Map<String, Object> body) {
        ToolDefinition current = registry.listDefinitions().stream()
                .filter(d -> d.getToolName().equals(name))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return ApiResponse.error(ErrorCode.NOT_FOUND, "工具不存在: " + name);
        }

        ToolDefinition def = new ToolDefinition();
        def.setToolName(name);
        def.setDescription(str(body, "description", current.getDescription()));
        def.setParameters(str(body, "parameters", current.getParameters()));
        def.setScope(str(body, "scope", current.getScope()));
        def.setEnabled(boolVal(body, "enabled", current.isEnabled()));

        repository.upsert(def);
        registry.applyDbOverrides();
        log.info("[ToolAdmin] 更新工具声明 {} enabled={} scope={}", name, def.isEnabled(), def.getScope());
        return ApiResponse.ok(Map.of("message", "工具声明已更新", "tool", def));
    }

    @Operation(summary = "删除工具声明（恢复代码默认声明）")
    @DeleteMapping("/{name}")
    public Object delete(@PathVariable String name) {
        ToolDefinition current = registry.listDefinitions().stream()
                .filter(d -> d.getToolName().equals(name))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return ApiResponse.error(ErrorCode.NOT_FOUND, "工具不存在: " + name);
        }
        repository.deleteByName(name);
        registry.applyDbOverrides();
        log.info("[ToolAdmin] 删除工具声明 {}（恢复代码默认）", name);
        return ApiResponse.ok(Map.of("message", "已恢复代码默认声明: " + name));
    }

    @Operation(summary = "重新应用 DB 声明（新增声明后热加载）")
    @GetMapping("/reload")
    public Object reload() {
        registry.applyDbOverrides();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", "DB 声明已重新应用");
        result.put("total", registry.listDefinitions().size());
        result.put("enabled", registry.listDefinitions().stream().filter(ToolDefinition::isEnabled).count());
        return ApiResponse.ok(result);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private static boolean boolVal(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }
}
