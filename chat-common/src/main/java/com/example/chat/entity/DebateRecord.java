package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "辩论记录")
public class DebateRecord {
    @Schema(description = "记录ID")
    public Long id;
    @Schema(description = "用户ID")
    public Long userId;
    @Schema(description = "用户名")
    public String userName;
    @Schema(description = "辩论问题")
    public String question;
    @Schema(description = "最终回答")
    public String finalAnswer;
    @Schema(description = "状态")
    public String status;
    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();
    @Schema(description = "更新时间")
    public java.time.Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getFinalAnswer() { return finalAnswer; }
    public void setFinalAnswer(String finalAnswer) { this.finalAnswer = finalAnswer; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }

    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }
}
