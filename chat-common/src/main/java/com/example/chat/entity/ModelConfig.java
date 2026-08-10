package com.example.chat.entity;

public class ModelConfig {
    public Long id;
    public String provider;
    public String model;
    public String apiKeyEncrypted;
    public String metaJson;
    public Integer priority = 100;
    public Boolean enabled = true;
    public java.time.Instant createdAt = java.time.Instant.now();
    /**
     * 模型执行类型：
     *   chat       - 对话（默认，id 1/2/3）
     *   image      - 图形生成（id 4）
     *   video      - 视频生成（id 5）
     *   text_parse - 文本解析（id 6）
     */
    public String modelType = "chat";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public void setApiKeyEncrypted(String apiKeyEncrypted) { this.apiKeyEncrypted = apiKeyEncrypted; }

    public String getMetaJson() { return metaJson; }
    public void setMetaJson(String metaJson) { this.metaJson = metaJson; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }

    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
}
