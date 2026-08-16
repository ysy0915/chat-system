package com.example.chat.llm.routing;

import java.util.Map;

/**
 * 模型路由配置 — 对应 llm_model_config 通用属性。
 */
public class ModelRoute {
    private Long   id;
    private String name;          // deepseek-chat
    private String displayName;   // DeepSeek V3 Chat
    private String modelType;     // chat / embedding / rerank / vision
    private int    maxTokens;
    private boolean enabled;
    private boolean isDefault;
    private int    priority;
    private String description;
    private Map<String, String> extraProps;

    public boolean matches(String model) {
        if (model == null || model.isBlank()) return isDefault;
        return name.equalsIgnoreCase(model);
    }

    // ── Getters/Setters ────────────────────────────────
    public Long   getId()             { return id; }
    public void   setId(Long id)      { this.id = id; }
    public String getName()           { return name; }
    public void   setName(String name){ this.name = name; }
    public String getDisplayName()    { return displayName; }
    public void   setDisplayName(String n) { this.displayName = n; }
    public String getModelType()      { return modelType; }
    public void   setModelType(String t) { this.modelType = t; }
    public int    getMaxTokens()      { return maxTokens; }
    public void   setMaxTokens(int m) { this.maxTokens = m; }
    public boolean isEnabled()        { return enabled; }
    public void   setEnabled(boolean e){ this.enabled = e; }
    public boolean isDefault()        { return isDefault; }
    public void   setDefault(boolean d){ this.isDefault = d; }
    public int    getPriority()       { return priority; }
    public void   setPriority(int p)  { this.priority = p; }
    public String getDescription()    { return description; }
    public void   setDescription(String d) { this.description = d; }
    public Map<String, String> getExtraProps() { return extraProps; }
    public void   setExtraProps(Map<String, String> m) { this.extraProps = m; }

    public String getProp(String key) {
        return extraProps != null ? extraProps.get(key) : null;
    }

    @Override public String toString() {
        return "ModelRoute{" + name + " type=" + modelType + " default=" + isDefault + "}";
    }
}
