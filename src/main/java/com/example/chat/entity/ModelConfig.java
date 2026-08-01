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
}
