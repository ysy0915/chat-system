package com.example.chat.entity;

public class TreeHoleMessage {
    public Long id;
    public String reqId;
    public Long userId;
    public String question;
    public String answerJson;
    public String status;
    public String mood;
    public java.time.Instant createdAt = java.time.Instant.now();
}
