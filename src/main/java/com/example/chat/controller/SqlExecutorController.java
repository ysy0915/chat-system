package com.example.chat.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sql")
public class SqlExecutorController {
    private static final Logger auditLog = LoggerFactory.getLogger("SQL_AUDIT");

    @Value("${sql-executor.password:19641025}")
    private String adminPassword;
    private static final Map<String, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
            "DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "CREATE", "SHUTDOWN", "DELETE"
    );

    private static final Set<String> READ_ONLY_KEYWORDS = Set.of(
            "SELECT", "SHOW", "DESC", "EXPLAIN"
    );

    private final DataSource dataSource;

    public SqlExecutorController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private boolean validToken(String token) {
        if (token == null) return false;
        Long ts = sessions.get(token);
        if (ts == null) return false;
        if (System.currentTimeMillis() - ts > 3600_000) { sessions.remove(token); return false; }
        sessions.put(token, System.currentTimeMillis());
        return true;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String clientIp = request.getHeader("X-Real-IP");
        if (clientIp == null) clientIp = request.getRemoteAddr();

        if (adminPassword != null && adminPassword.equals(body.get("password"))) {
            String token = UUID.randomUUID().toString();
            sessions.put(token, System.currentTimeMillis());
            auditLog.info("[SQL_AUDIT] LOGIN_SUCCESS ip={}", clientIp);
            return ResponseEntity.ok(Map.of("token", token));
        }
        auditLog.warn("[SQL_AUDIT] LOGIN_FAILED ip={}", clientIp);
        return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     @RequestBody Map<String, String> body,
                                     HttpServletRequest request) {
        String clientIp = request.getHeader("X-Real-IP");
        if (clientIp == null) clientIp = request.getRemoteAddr();

        if (!validToken(token)) {
            auditLog.warn("[SQL_AUDIT] UNAUTHORIZED_EXECUTE ip={}", clientIp);
            return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        }

        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "SQL不能为空"));
        if (sql.length() > 5000) return ResponseEntity.badRequest().body(Map.of("error", "SQL长度不能超过5000字符"));

        String upperSql = sql.trim().toUpperCase();
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (upperSql.contains(keyword)) {
                auditLog.warn("[SQL_AUDIT] DANGEROUS_SQL_BLOCKED ip={} sql={}", clientIp, sql.substring(0, Math.min(200, sql.length())));
                return ResponseEntity.badRequest().body(Map.of("error", "禁止执行危险SQL: " + keyword));
            }
        }

        boolean isReadOnly = READ_ONLY_KEYWORDS.stream().anyMatch(upperSql::startsWith);
        auditLog.info("[SQL_AUDIT] EXECUTE ip={} readOnly={} sql={}", clientIp, isReadOnly, sql.substring(0, Math.min(200, sql.length())));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        int affectedRows = -1;
        String error = null;

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(30);
                if (isReadOnly) {
                    ResultSet rs = stmt.executeQuery(sql);
                    ResultSetMetaData meta = rs.getMetaData();
                    int maxRows = 1000;
                    int rowCount = 0;
                    for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
                    while (rs.next() && rowCount < maxRows) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            Object val = rs.getObject(i);
                            row.put(meta.getColumnName(i), val == null ? "NULL" : val.toString());
                        }
                        rows.add(row);
                        rowCount++;
                    }
                    rs.close();
                } else {
                    affectedRows = stmt.executeUpdate(sql);
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
            auditLog.error("[SQL_AUDIT] EXECUTE_ERROR ip={} error={}", clientIp, error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("affectedRows", affectedRows);
        result.put("error", error);
        return ResponseEntity.ok(result);
    }
}
