package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "树洞消息")
public class TreeHoleMessage {
    @Schema(description = "消息ID")
    public Long id;
    @Schema(description = "请求追踪ID")
    public String reqId;
    @Schema(description = "用户ID")
    public Long userId;
    @Schema(description = "用户提问")
    public String question;
    @Schema(description = "回答 JSON")
    public String answerJson;
    @Schema(description = "状态")
    public String status;
    @Schema(description = "情绪标签", example = "开心")
    public String mood;
    @Schema(description = "LLM 提供商")
    public String provider;
    @Schema(description = "使用的模型")
    public String model;
    @Schema(description = "Token 消耗数")
    public Integer tokens;
    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReqId() { return reqId; }
    public void setReqId(String reqId) { this.reqId = reqId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswerJson() { return answerJson; }
    public void setAnswerJson(String answerJson) { this.answerJson = answerJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMood() { return mood; }
    public void setMood(String mood) { this.mood = mood; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getTokens() { return tokens; }
    public void setTokens(Integer tokens) { this.tokens = tokens; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
