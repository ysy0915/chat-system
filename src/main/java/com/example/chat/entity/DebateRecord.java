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
}
