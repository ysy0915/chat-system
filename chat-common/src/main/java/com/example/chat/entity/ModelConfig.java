package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "LLM 模型配置")
public class ModelConfig {
    @Schema(description = "配置ID")
    public Long id;
    @Schema(description = "模型提供商", example = "openai")
    public String provider;
    @Schema(description = "模型名称", example = "gpt-4o")
    public String model;
    @Schema(description = "加密的 API Key")
    public String apiKeyEncrypted;
    @Schema(description = "额外元数据 JSON")
    public String metaJson;
    @Schema(description = "优先级", example = "100")
    public Integer priority = 100;
    @Schema(description = "是否启用")
    public Boolean enabled = true;
    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();
    /**
     * 模型执行类型：
     *   chat       - 对话（默认，id 1/2/3）
     *   image      - 图形生成（id 4）
     *   video      - 视频生成（id 5）
     *   text_parse - 文本解析（id 6）
     */
    @Schema(description = "模型类型: chat / image / video / text_parse", example = "chat")
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
