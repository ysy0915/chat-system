package com.example.chat.entity;

public class Message {
    public Long id;
    public String reqId;
    public Long userId;
    public String question;
    public String answerJson; // JSON stored as text
    public String status; // queued/processing/done/failed
    public String provider;
    public String model;
    public java.time.Instant createdAt = java.time.Instant.now();
}
