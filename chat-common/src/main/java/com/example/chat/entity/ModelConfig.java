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
}
