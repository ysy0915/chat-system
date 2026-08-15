package com.example.chat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class AdminAuthUtil {

    @Value("${monitor.password:}")
    private String monitorPassword;

    @Value("${sql-executor.password:}")
    private String sqlExecutorPassword;

    public boolean checkMonitorPassword(String password) {
        return constantTimeEquals(monitorPassword, password);
    }

    public boolean checkSqlExecutorPassword(String password) {
        return constantTimeEquals(sqlExecutorPassword, password);
    }

    /**
     * 常量时间字符串比较：消除 String.equals 在首个字符不等时提前返回导致的时序侧信道，
     * 使攻击者无法通过响应时间逐字符猜测密码。
     * <p>空密码不参与比较，直接判 false（未配置密码时管理接口一律拒绝）。</p>
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
