package com.example.chat.llm.routing.db;

/**
 * <h2>大模型提供商配置行</h2>
 *
 * <p>对应 <code>llm_provider_config</code> 表，供模型管理面读写
 * （YAML 兜底 + DB 覆盖的运行时数据来源）。</p>
 */
public class LlmProviderRow {

    private Long id;
    private String providerName;
    private String baseUrl;
    private String authType;
    private String invokeType;
    private Boolean enabled;
    private Boolean isDefault;
    private Integer priority;
    private String description;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public String getInvokeType() { return invokeType; }
    public void setInvokeType(String invokeType) { this.invokeType = invokeType; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
