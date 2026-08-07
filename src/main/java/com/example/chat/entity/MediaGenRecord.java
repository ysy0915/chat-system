package com.example.chat.entity;

public class MediaGenRecord {
    public Long id;
    public Long userId;
    public String prompt;
    public String mediaType;   // image / video / 3d
    public String model;
    public String mediaUrl;
    public String glbUrl;
    public String objUrl;
    public String previewUrl;
    public String status;
    public String errorMsg;
    public java.time.Instant createdAt = java.time.Instant.now();
}
