package com.example.chat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/sql")
public class SqlExecutorController {
    private static final String ADMIN_PASSWORD = "19641025";
    private static final Map<String, Long> sessions = new java.util.concurrent.ConcurrentHashMap<>();

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
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        if (ADMIN_PASSWORD.equals(body.get("password"))) {
            String token = UUID.randomUUID().toString();
            sessions.put(token, System.currentTimeMillis());
            return ResponseEntity.ok(Map.of("token", token));
        }
        return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
    }

    @PostMapping("/execute")
    public ResponseEntity<?> execute(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                     @RequestBody Map<String, String> body) {
        if (!validToken(token)) return ResponseEntity.status(401).body(Map.of("error", "未授权"));
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "SQL不能为空"));

        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        int affectedRows = -1;
        String error = null;

        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(0);
                String trimmed = sql.trim().toUpperCase();
                if (trimmed.startsWith("SELECT") || trimmed.startsWith("SHOW") || trimmed.startsWith("DESC") || trimmed.startsWith("EXPLAIN")) {
                    ResultSet rs = stmt.executeQuery(sql);
                    ResultSetMetaData meta = rs.getMetaData();
                    for (int i = 1; i <= meta.getColumnCount(); i++) columns.add(meta.getColumnName(i));
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            Object val = rs.getObject(i);
                            row.put(meta.getColumnName(i), val == null ? "NULL" : val.toString());
                        }
                        rows.add(row);
                    }
                    rs.close();
                } else {
                    affectedRows = stmt.executeUpdate(sql);
                }
            }
        } catch (Exception e) {
            error = e.getMessage();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", columns);
        result.put("rows", rows);
        result.put("affectedRows", affectedRows);
        result.put("error", error);
        return ResponseEntity.ok(result);
    }
}
