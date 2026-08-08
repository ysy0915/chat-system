package com.example.chat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdminAuthUtil {

    @Value("${monitor.password:19641025}")
    private String monitorPassword;

    @Value("${sql-executor.password:LiYuHong@0929}")
    private String sqlExecutorPassword;

    public boolean checkMonitorPassword(String password) {
        return monitorPassword.equals(password);
    }

    public boolean checkSqlExecutorPassword(String password) {
        return sqlExecutorPassword.equals(password);
    }
}
