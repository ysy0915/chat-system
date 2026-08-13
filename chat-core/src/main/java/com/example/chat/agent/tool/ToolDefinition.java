package com.example.chat.agent.tool;

/**
 * <h2>工具元数据定义（工具平台化核心值对象）</h2>
 *
 * <p>[B档] 工具平台化：将工具的<b>声明元数据</b>（名称/描述/参数 Schema/启用/范围）从
 * 工具实现中抽出为独立值对象，支持 <b>DB 注册表（tool_registry）覆盖代码默认声明</b>：
 * <ul>
 *   <li>代码内置工具注册时生成 source=CODE 的定义（取实现类声明）</li>
 *   <li>DB 声明（source=DB）按 toolName 合并覆盖：可禁用工具、替换描述/参数 Schema、限定范围</li>
 *   <li>运行时仅暴露 enabled 工具（schema 生成 / 执行分发都过滤禁用项）</li>
 * </ul>
 * </p>
 */
public class ToolDefinition {

    /** 来源：代码内置 */
    public static final String SOURCE_CODE = "CODE";
    /** 来源：DB 管理面声明 */
    public static final String SOURCE_DB = "DB";

    /** 工具名（唯一，与 Tool.getName() 一致） */
    private String toolName;

    /** 工具描述（给 AI 看） */
    private String description;

    /** 参数 JSON Schema 字符串（给 AI 看） */
    private String parameters;

    /** 是否启用（false = 运行时不可见不可调用） */
    private boolean enabled = true;

    /** 可见范围（* = 全部 / chat / subagent / 自定义场景） */
    private String scope = "*";

    /** 声明来源：CODE（代码内置）/ DB（管理面声明） */
    private String source = SOURCE_CODE;

    public ToolDefinition() {
    }

    public ToolDefinition(String toolName, String description, String parameters) {
        this.toolName = toolName;
        this.description = description;
        this.parameters = parameters;
    }

    /** 从代码工具实现构建默认声明（source=CODE，enabled=true） */
    public static ToolDefinition fromCode(Tool tool) {
        ToolDefinition def = new ToolDefinition(tool.getName(), tool.getDescription(), tool.getParameters());
        def.setSource(SOURCE_CODE);
        return def;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
