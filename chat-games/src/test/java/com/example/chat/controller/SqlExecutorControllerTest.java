package com.example.chat.controller;

import com.example.chat.security.RateLimitChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SqlExecutorController 真实行为断言（安全关键路径）：
 * 登录成功/失败/锁定、未授权执行 401、空 SQL/危险 SQL/多语句 400、合法 SELECT 返回行集。
 */
@ExtendWith(MockitoExtension.class)
class SqlExecutorControllerTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private RateLimitChecker rateLimitChecker;

    @Mock
    private HttpServletRequest request;

    private SqlExecutorController controller;

    @BeforeEach
    void setUp() {
        controller = new SqlExecutorController(dataSource, rateLimitChecker);
        ReflectionTestUtils.setField(controller, "adminPassword", "secret");
    }

    /** 通过真实 login 流程获取合法 token */
    private String loginAndGetToken() {
        when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
        when(rateLimitChecker.getCount("rate:sql-login:1.2.3.4")).thenReturn(0L);
        ResponseEntity<?> resp = controller.login(Map.of("password", "secret"), request);
        assertEquals(200, resp.getStatusCode().value());
        return (String) ((Map<?, ?>) resp.getBody()).get("token");
    }

    // ────────── login ──────────

    @Test
    void login_correctPassword_returnsTokenAndResetsCounter() {
        when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
        when(rateLimitChecker.getCount("rate:sql-login:1.2.3.4")).thenReturn(0L);

        ResponseEntity<?> resp = controller.login(Map.of("password", "secret"), request);

        assertEquals(200, resp.getStatusCode().value());
        assertNotNull(((Map<?, ?>) resp.getBody()).get("token"));
        verify(rateLimitChecker).reset("rate:sql-login:1.2.3.4");
    }

    @Test
    void login_wrongPassword_returns401() {
        when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
        when(rateLimitChecker.getCount("rate:sql-login:1.2.3.4")).thenReturn(0L);
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(true);

        ResponseEntity<?> resp = controller.login(Map.of("password", "wrong"), request);

        assertEquals(401, resp.getStatusCode().value());
        verify(rateLimitChecker).checkAndIncrement(eq("rate:sql-login:1.2.3.4"), eq(5), any());
    }

    @Test
    void login_lockedAfterFiveFailures_returns429() {
        when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
        when(rateLimitChecker.getCount("rate:sql-login:1.2.3.4")).thenReturn(5L);

        ResponseEntity<?> resp = controller.login(Map.of("password", "secret"), request);

        assertEquals(429, resp.getStatusCode().value());
        verify(rateLimitChecker, never()).reset(anyString());
    }

    // ────────── execute ──────────

    @Test
    void execute_invalidToken_returns401() {
        ResponseEntity<?> resp = controller.execute("bad-token", Map.of("sql", "SELECT 1"), request);

        assertEquals(401, resp.getStatusCode().value());
        verify(rateLimitChecker, never()).checkAndIncrement(anyString(), anyInt(), any());
    }

    @Test
    void execute_blankSql_returns400() {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement("rate:sql-exec:1.2.3.4", 30, Duration.ofMinutes(1)))
                .thenReturn(true);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", "   "), request);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void execute_dangerousSql_returns400() {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(true);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", "DROP TABLE users"), request);

        assertEquals(400, resp.getStatusCode().value());
        assertTrue(((Map<?, ?>) resp.getBody()).toString().contains("禁止执行危险SQL"));
    }

    @Test
    void execute_multiStatement_returns400() {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(true);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", "SELECT 1; DROP TABLE t"), request);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void execute_oversizedSql_returns400() {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(true);
        String bigSql = "SELECT 1" + " ".repeat(6000);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", bigSql), request);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void execute_validSelect_returnsRows() throws Exception {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(true);

        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery("SELECT id FROM users")).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnName(1)).thenReturn("id");
        when(rs.next()).thenReturn(true, false);
        when(rs.getObject(1)).thenReturn(42);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", "SELECT id FROM users"), request);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(List.of("id"), body.get("columns"));
        assertEquals(1, ((List<?>) body.get("rows")).size());
        assertEquals("42", ((Map<?, ?>) ((List<?>) body.get("rows")).get(0)).get("id"));
        assertNull(body.get("error"));
    }

    @Test
    void execute_ratelimited_returns429() {
        String token = loginAndGetToken();
        when(rateLimitChecker.checkAndIncrement(anyString(), anyInt(), any())).thenReturn(false);

        ResponseEntity<?> resp = controller.execute(token, Map.of("sql", "SELECT 1"), request);

        assertEquals(429, resp.getStatusCode().value());
    }
}
