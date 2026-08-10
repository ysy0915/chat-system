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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getMediaType() { return mediaType; }
    public void setMediaType(String mediaType) { this.mediaType = mediaType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getMediaUrl() { return mediaUrl; }
    public void setMediaUrl(String mediaUrl) { this.mediaUrl = mediaUrl; }

    public String getGlbUrl() { return glbUrl; }
    public void setGlbUrl(String glbUrl) { this.glbUrl = glbUrl; }

    public String getObjUrl() { return objUrl; }
    public void setObjUrl(String objUrl) { this.objUrl = objUrl; }

    public String getPreviewUrl() { return previewUrl; }
    public void setPreviewUrl(String previewUrl) { this.previewUrl = previewUrl; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMsg() { return errorMsg; }
    public void setErrorMsg(String errorMsg) { this.errorMsg = errorMsg; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
