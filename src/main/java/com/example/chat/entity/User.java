package com.example.chat.entity;

public class User {
    public Long id;
    public String email;
    public String passwordHash;
    public String name;
    public String nickname;
    public String guestName;
    public String role = "user";
    public java.time.Instant createdAt = java.time.Instant.now();
}
