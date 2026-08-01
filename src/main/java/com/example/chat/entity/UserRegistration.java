package com.example.chat.entity;

public class UserRegistration {
    public Long id;
    public Long userId;
    public String email;
    public String username;
    public String nickname;
    public java.time.Instant registeredAt = java.time.Instant.now();
}
