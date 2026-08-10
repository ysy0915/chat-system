package com.example.chat.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "审计日志")
public class AuditLog {
    @Schema(description = "日志ID")
    public Long id;
    @Schema(description = "事件类型: LOGIN, SQL_EXECUTE, SENSITIVE_BLOCKED 等")
    public String eventType;
    @Schema(description = "用户ID")
    public String userId;
    @Schema(description = "用户名")
    public String username;
    @Schema(description = "客户端IP")
    public String ipAddress;
    @Schema(description = "User-Agent")
    public String userAgent;
    @Schema(description = "操作详情")
    public String detail;
    @Schema(description = "结果: SUCCESS / FAILED / BLOCKED")
    public String result;
    @Schema(description = "创建时间")
    public LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
