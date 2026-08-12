package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户画像（L3）—— 结构化偏好存储（MySQL user_profiles 表）。
 *
 * <p>与 Redis 中临时画像（user_profile:{scene}:{userId}）配合：
 * Redis 提供快速读取，本表负责持久化，重启不丢失。</p>
 */
@Schema(description = "用户画像")
public class UserProfile {

    @Schema(description = "主键")
    public Long id;

    @Schema(description = "用户ID")
    public Long userId;

    @Schema(description = "场景 personal / treehole / chat")
    public String scene = "personal";

    @Schema(description = "用户当前情景（一句话）")
    public String sceneDesc;

    @Schema(description = "情绪列表 JSON 数组")
    public String emotionsJson;

    @Schema(description = "偏好列表 JSON 数组")
    public String preferencesJson;

    @Schema(description = "背景信息列表 JSON 数组")
    public String contextsJson;

    @Schema(description = "累计来源对话数")
    public Integer sourceCount = 0;

    @Schema(description = "更新时间")
    public java.time.Instant updatedAt = java.time.Instant.now();

    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getScene() { return scene; }
    public void setScene(String scene) { this.scene = scene; }

    public String getSceneDesc() { return sceneDesc; }
    public void setSceneDesc(String sceneDesc) { this.sceneDesc = sceneDesc; }

    public String getEmotionsJson() { return emotionsJson; }
    public void setEmotionsJson(String emotionsJson) { this.emotionsJson = emotionsJson; }

    public String getPreferencesJson() { return preferencesJson; }
    public void setPreferencesJson(String preferencesJson) { this.preferencesJson = preferencesJson; }

    public String getContextsJson() { return contextsJson; }
    public void setContextsJson(String contextsJson) { this.contextsJson = contextsJson; }

    public Integer getSourceCount() { return sourceCount; }
    public void setSourceCount(Integer sourceCount) { this.sourceCount = sourceCount; }

    public java.time.Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(java.time.Instant updatedAt) { this.updatedAt = updatedAt; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
