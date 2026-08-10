package com.example.chat.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AdminAuthUtil 单元测试
 * 覆盖：监控密码校验、SQL 执行器密码校验、默认值、空/null 密码
 */
class AdminAuthUtilTest {

    private AdminAuthUtil adminAuthUtil;

    @BeforeEach
    void setUp() {
        adminAuthUtil = new AdminAuthUtil();
        // 注入自定义密码（覆盖 @Value 默认值）
        ReflectionTestUtils.setField(adminAuthUtil, "monitorPassword", "testMonitor123");
        ReflectionTestUtils.setField(adminAuthUtil, "sqlExecutorPassword", "testSql456");
    }

    // ---------- checkMonitorPassword ----------

    @Test
    @DisplayName("监控密码正确时返回 true")
    void checkMonitorPassword_correct() {
        assertTrue(adminAuthUtil.checkMonitorPassword("testMonitor123"));
    }

    @Test
    @DisplayName("监控密码错误时返回 false")
    void checkMonitorPassword_wrong() {
        assertFalse(adminAuthUtil.checkMonitorPassword("wrongPassword"));
    }

    @Test
    @DisplayName("监控密码为 null 时返回 false")
    void checkMonitorPassword_nullInput() {
        assertFalse(adminAuthUtil.checkMonitorPassword(null));
    }

    @Test
    @DisplayName("监控密码为空字符串时返回 false")
    void checkMonitorPassword_emptyInput() {
        assertFalse(adminAuthUtil.checkMonitorPassword(""));
    }

    // ---------- checkSqlExecutorPassword ----------

    @Test
    @DisplayName("SQL 执行器密码正确时返回 true")
    void checkSqlExecutorPassword_correct() {
        assertTrue(adminAuthUtil.checkSqlExecutorPassword("testSql456"));
    }

    @Test
    @DisplayName("SQL 执行器密码错误时返回 false")
    void checkSqlExecutorPassword_wrong() {
        assertFalse(adminAuthUtil.checkSqlExecutorPassword("wrongPassword"));
    }

    @Test
    @DisplayName("SQL 执行器密码为 null 时返回 false")
    void checkSqlExecutorPassword_nullInput() {
        assertFalse(adminAuthUtil.checkSqlExecutorPassword(null));
    }

    @Test
    @DisplayName("两个密码相互独立（不混淆）")
    void passwords_areIndependent() {
        assertTrue(adminAuthUtil.checkMonitorPassword("testMonitor123"));
        assertFalse(adminAuthUtil.checkMonitorPassword("testSql456"));
        assertTrue(adminAuthUtil.checkSqlExecutorPassword("testSql456"));
        assertFalse(adminAuthUtil.checkSqlExecutorPassword("testMonitor123"));
    }
}
