package com.example.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Tag(name = "SQL执行器", description = "游戏内 SQL 执行与调试接口")
@RestController
@RequestMapping("/api/v1/sql")
public class SqlExecutorController {
    private static final Logger auditLog = LoggerFactory.getLogger("SQL_AUDIT");

    @Value("${sql-executor.password:}")
    private String adminPassword;
    private static final Map<String, Long> sessions = new ConcurrentHashMap<>();

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

    @Operation(summary = "SQL执行器登录", description = "使用管理员密码登录，获取执行SQL的临时令牌")
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Parameter(description = "请求体，包含 password 字段") @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String clientIp = request.getHeader("X-Real-IP");
        if (clientIp == null) clientIp = request.getRemoteAddr();

        if (adminPassword != null && !adminPassword.isBlank() && adminPassword.equals(body.get("password"))) {
            String token = UUID.randomUUID().toString();
            sessions.put(token, System.currentTimeMillis());
            auditLog.info("[SQL_AUDIT] LOGIN_SUCCESS ip={}", clientIp);
            return ResponseEntity.ok(Map.of("token", token));
        }
        auditLog.warn("[SQL_AUDIT] LOGIN_FAILED ip={}", clientIp);
        return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
    }

    @Operation(summary = "执行SQL语句", description = "执行SQL查询或更新语句，禁止执行DROP/TRUNCATE等危险操作")
    @PostMapping("/execute")
    public ResponseEntity<?> execute(
            @Parameter(description = "管理员令牌，登录后获取") @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @Parameter(description = "请求体，包含 sql 字段（长度不超过5000）") @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String clientIp = request.getHeader("X-Real-IP");
        if (clientIp == null) clientIp = request.getRemoteAddr();

        if (!validToken(token)) {
            auditLog.warn("[SQL_AUDIT] UNAUTHORIZED_EXECUTE ip={}", clientIp);
            return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        }

        String sql = body.get("sql");
        ResponseEntity<?> validationError = validateSql(sql, clientIp);
        if (validationError != null) return validationError;

        boolean isReadOnly = READ_ONLY_KEYWORDS.stream().anyMatch(sql.trim().toUpperCase(Locale.ROOT)::startsWith);
        auditLog.info("[SQL_AUDIT] EXECUTE ip={} readOnly={} sql={}", clientIp, isReadOnly, sql.substring(0, Math.min(200, sql.length())));

        Map<String, Object> result = executeSql(sql, isReadOnly, clientIp);
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> validateSql(String sql, String clientIp) {
        if (sql == null || sql.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "SQL不能为空"));
        if (sql.length() > 5000) return ResponseEntity.badRequest().body(Map.of("error", "SQL长度不能超过5000字符"));

        String upperSql = sql.trim().toUpperCase(Locale.ROOT);
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (upperSql.contains(keyword)) {
                auditLog.warn("[SQL_AUDIT] DANGEROUS_SQL_BLOCKED ip={} sql={}", clientIp, sql.substring(0, Math.min(200, sql.length())));
                return ResponseEntity.badRequest().body(Map.of("error", "禁止执行危险SQL: " + keyword));
            }
        }
        return null;
    }

    private Map<String, Object> executeSql(String sql, boolean isReadOnly, String clientIp) {

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
        } catch (SQLException e) {
            error = e.getMessage();
            auditLog.error("[SQL_AUDIT] EXECUTE_ERROR ip={} error={}", clientIp, error);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("affectedRows", affectedRows);
        result.put("error", error);
        return result;
    }
}
