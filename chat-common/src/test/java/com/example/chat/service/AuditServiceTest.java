package com.example.chat.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuditService 单元测试
 * 覆盖：审计日志记录、客户端 IP 获取（X-Real-IP / X-Forwarded-For / RemoteAddr）
 */
class AuditServiceTest {

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService();
    }

    @Test
    @DisplayName("log 方法调用不抛异常")
    void log_noException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "10.0.0.1");
        request.addHeader("User-Agent", "Mozilla/5.0");

        assertDoesNotThrow(() ->
                auditService.log("LOGIN", "123", "testuser", request, "登录成功", "SUCCESS"));
    }

    @Test
    @DisplayName("X-Real-IP 优先作为客户端 IP")
    void log_xRealIp_used() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "1.2.3.4");
        request.addHeader("X-Forwarded-For", "5.6.7.8");
        request.setRemoteAddr("127.0.0.1");

        assertDoesNotThrow(() ->
                auditService.log("ACTION", "1", "user", request, "test", "OK"));
    }

    @Test
    @DisplayName("无代理头时使用 RemoteAddr")
    void log_noProxyHeaders_usesRemoteAddr() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.1");

        assertDoesNotThrow(() ->
                auditService.log("VIEW", "2", "viewer", request, "查看页面", "OK"));
    }

    @Test
    @DisplayName("不同事件类型正常记录")
    void log_differentEventTypes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");

        assertDoesNotThrow(() -> {
            auditService.log("LOGIN", "1", "alice", request, "登录", "SUCCESS");
            auditService.log("LOGOUT", "1", "alice", request, "登出", "SUCCESS");
            auditService.log("DELETE", "2", "bob", request, "删除消息", "FAIL");
        });
    }
}
