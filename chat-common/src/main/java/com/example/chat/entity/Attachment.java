package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "消息附件")
public class Attachment {
    @Schema(description = "附件ID", example = "1")
    public Long id;
    @Schema(description = "关联消息ID")
    public Long messageId;
    @Schema(description = "上传者用户ID")
    public Long uploadedBy;
    @Schema(description = "存储URL")
    public String storageUrl;
    @Schema(description = "MIME类型", example = "image/png")
    public String mimeType;
    @Schema(description = "文件名", example = "screenshot.png")
    public String filename;
    @Schema(description = "文件大小(字节)", example = "102400")
    public Long size;
    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }

    public String getStorageUrl() { return storageUrl; }
    public void setStorageUrl(String storageUrl) { this.storageUrl = storageUrl; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
