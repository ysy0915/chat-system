package com.example.chat.entity;

public class Attachment {
    public Long id;
    public Long messageId;
    public Long uploadedBy;
    public String storageUrl;
    public String mimeType;
    public String filename;
    public Long size;
    public java.time.Instant createdAt = java.time.Instant.now();
}
