package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户")
public class User {
    @Schema(description = "用户ID")
    public Long id;
    @Schema(description = "邮箱")
    public String email;
    @Schema(description = "密码哈希", accessMode = Schema.AccessMode.WRITE_ONLY)
    public String passwordHash;
    @Schema(description = "姓名")
    public String name;
    @Schema(description = "昵称")
    public String nickname;
    @Schema(description = "游客名")
    public String guestName;
    @Schema(description = "角色: user / admin", example = "user")
    public String role = "user";
    @Schema(description = "创建时间")
    public java.time.Instant createdAt = java.time.Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public java.time.Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(java.time.Instant createdAt) { this.createdAt = createdAt; }
}
