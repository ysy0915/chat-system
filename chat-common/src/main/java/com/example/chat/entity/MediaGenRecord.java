package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "媒体生成记录")
public class MediaGenRecord {
    @Schema(description = "记录ID")
    public Long id;
    @Schema(description = "用户ID")
    public Long userId;
    @Schema(description = "生成提示词")
    public String prompt;
    @Schema(description = "媒体类型: image / video / 3d")
    public String mediaType;
    @Schema(description = "使用的模型")
    public String model;
    @Schema(description = "生成结果URL")
    public String mediaUrl;
    @Schema(description = "3D模型 GLB 格式URL")
    public String glbUrl;
    @Schema(description = "3D模型 OBJ 格式URL")
    public String objUrl;
    @Schema(description = "预览图URL")
    public String previewUrl;
    @Schema(description = "状态: pending / processing / done / error")
    public String status;
    @Schema(description = "错误信息")
    public String errorMsg;
    @Schema(description = "创建时间")
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
