package com.example.chat.entity;

public class DebateRecord {
    public Long id;
    public Long userId;
    public String userName;
    public String question;
    public String finalAnswer;
    public String status;
    public java.time.Instant createdAt = java.time.Instant.now();
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
