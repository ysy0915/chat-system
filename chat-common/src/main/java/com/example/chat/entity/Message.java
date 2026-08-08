package com.example.chat.entity;

public class Message {
    public Long id;
    public String reqId;
    public Long userId;
    public String question;
    public String summary;
    public String answerJson;
    public String status;
    public String provider;
    public String model;
    public Integer tokens;
    public Integer isPrivate = 0;
    public java.time.Instant createdAt = java.time.Instant.now();
}
