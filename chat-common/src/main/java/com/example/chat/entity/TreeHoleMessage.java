package com.example.chat.entity;

public class TreeHoleMessage {
    public Long id;
    public String reqId;
    public Long userId;
    public String question;
    public String answerJson;
    public String status;
    public String mood;
    public String provider;
    public String model;
    public Integer tokens;
    public java.time.Instant createdAt = java.time.Instant.now();
}
