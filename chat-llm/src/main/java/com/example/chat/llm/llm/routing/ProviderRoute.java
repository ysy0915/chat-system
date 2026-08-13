package com.example.chat.llm.llm.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 提供商路由配置 — 对应 llm_provider_config + llm_provider_props 两张表。
 * 内部持有该提供商下的所有 {@link ModelRoute}。
 */
public class ProviderRoute {
    private Long   id;
    private String name;          // deepseek / qwen / doubao
    private String baseUrl;
    private String authType;
    private String invokeType;    // rest (HTTP REST API) / sdk (OpenAI SDK)
    private String apiKey;        // 从 KV 表 SECRET 字段提取
    private boolean enabled;
    private boolean isDefault;
    private int    priority;
    private String description;
    private Map<String, String> extraProps;

    /** 该提供商下的已注册模型列表 */
    private List<ModelRoute> models = new ArrayList<>();

    // ── 模型路由 ─────────────────────────────────────────

    /** 按模型名精确匹配 */
    public ModelRoute matchModel(String model) {
        // 精确匹配
        for (ModelRoute m : models) {
            if (m.isEnabled() && m.matches(model)) return m;
        }
        // fallback: 默认模型
        return getDefaultModel();
    }

    /** 获取默认模型 */
    public ModelRoute getDefaultModel() {
        return models.stream()
                .filter(ModelRoute::isEnabled)
                .filter(ModelRoute::isDefault)
                .findFirst()
                .orElseGet(() -> models.stream()
                        .filter(ModelRoute::isEnabled)
                        .min((a, b) -> a.getPriority() - b.getPriority())
                        .orElse(null));
    }

    /** 按类型列出模型 */
    public List<ModelRoute> listModelsByType(String modelType) {
        return models.stream()
                .filter(ModelRoute::isEnabled)
                .filter(m -> modelType == null || m.getModelType().equalsIgnoreCase(modelType))
                .toList();
    }

    public void addModel(ModelRoute model) { this.models.add(model); }

    // ── Getters/Setters ────────────────────────────────
    public Long   getId()             { return id; }
    public void   setId(Long id)      { this.id = id; }
    public String getName()           { return name; }
    public void   setName(String n)   { this.name = n; }
    public String getBaseUrl()        { return baseUrl; }
    public void   setBaseUrl(String u) { this.baseUrl = u; }
    public String getAuthType()       { return authType; }
    public void   setAuthType(String t) { this.authType = t; }
    public String getInvokeType()      { return invokeType; }
    public void   setInvokeType(String t) { this.invokeType = t; }
    /** 是否为 SDK 调用方式 */
    public boolean isSdk()             { return "sdk".equalsIgnoreCase(invokeType); }
    /** 是否为 REST API 调用方式 */
    public boolean isRest()            { return invokeType == null || "rest".equalsIgnoreCase(invokeType); }
    public String getApiKey()         { return apiKey; }
    public void   setApiKey(String k) { this.apiKey = k; }
    public boolean isEnabled()        { return enabled; }
    public void   setEnabled(boolean e) { this.enabled = e; }
    public boolean isDefault()        { return isDefault; }
    public void   setDefault(boolean d) { this.isDefault = d; }
    public int    getPriority()       { return priority; }
    public void   setPriority(int p)  { this.priority = p; }
    public String getDescription()    { return description; }
    public void   setDescription(String d) { this.description = d; }
    public Map<String, String> getExtraProps() { return extraProps; }
    public void   setExtraProps(Map<String, String> m) { this.extraProps = m; }
    public List<ModelRoute> getModels() { return models; }
    public void   setModels(List<ModelRoute> m) { this.models = m; }

    public String getProp(String key) {
        return extraProps != null ? extraProps.get(key) : null;
    }

    @Override public String toString() {
        return "ProviderRoute{" + name + " base=" + baseUrl + " models=" + models.size() + " default=" + isDefault + "}";
    }
}
