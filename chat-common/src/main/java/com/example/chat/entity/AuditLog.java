package com.example.chat.entity;

import java.time.LocalDateTime;

public class AuditLog {
    public Long id;
    public String eventType;    // LOGIN, SQL_EXECUTE, SENSITIVE_BLOCKED, etc.
    public String userId;
    public String username;
    public String ipAddress;
    public String userAgent;
    public String detail;       // 操作详情
    public String result;       // SUCCESS, FAILED, BLOCKED
    public LocalDateTime createdAt;
}
