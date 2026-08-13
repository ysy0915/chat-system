package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.security.AuthUtils;
import com.example.chat.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TreeHoleController 真实行为断言：
 * JWT 401 守卫、ask 空白问题 400、提问转发、regenerate/stop 守卫与转发。
 */
@ExtendWith(MockitoExtension.class)
class TreeHoleControllerTest {

    @Mock
    private CoreClient coreClient;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private HttpServletRequest request;

    private TreeHoleController controller;

    @BeforeEach
    void setUp() {
        controller = new TreeHoleController(coreClient, jwtUtil);
    }

    @Test
    void history_noAuth_401() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(null);

            ResponseEntity<?> resp = controller.history(request);

            assertEquals(401, resp.getStatusCode().value());
            verify(coreClient, never()).treeHoleRecent(any());
        }
    }

    @Test
    void history_success_returnsRecent() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);
            when(coreClient.treeHoleRecent(7L)).thenReturn(Map.of("rows", 1));

            ResponseEntity<?> resp = controller.history(request);

            assertEquals(200, resp.getStatusCode().value());
            assertEquals(1, ((Map<?, ?>) resp.getBody()).get("rows"));
            verify(coreClient).treeHoleRecent(7L);
        }
    }

    @Test
    void ask_blankQuestion_400() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);

            ResponseEntity<?> resp = controller.ask(Map.of("question", "  "), request);

            assertEquals(400, resp.getStatusCode().value());
            verify(coreClient, never()).treeHoleAsk(any(), any(), any());
        }
    }

    @Test
    void ask_success_callsTreeHoleAsk() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);

            ResponseEntity<?> resp = controller.ask(Map.of("question", "你好"), request);

            assertEquals(200, resp.getStatusCode().value());
            verify(coreClient).treeHoleAsk(eq(7L), eq("你好"), anyString());
            Map<?, ?> body = (Map<?, ?>) resp.getBody();
            assertEquals("streaming", body.get("status"));
            assertNotNull(body.get("req_id"));
        }
    }

    @Test
    void regenerate_blankReqId_400() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);

            ResponseEntity<?> resp = controller.regenerate(Map.of("req_id", " "), request);

            assertEquals(400, resp.getStatusCode().value());
            verify(coreClient, never()).treeHoleRegenerate(any(), any());
        }
    }

    @Test
    void stop_blankReqId_skipsStopCall() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);

            ResponseEntity<?> resp = controller.stop(Map.of("req_id", "  "), request);

            assertEquals(200, resp.getStatusCode().value());
            verify(coreClient, never()).treeHoleStop(any());
        }
    }

    @Test
    void stop_success_callsTreeHoleStop() {
        try (MockedStatic<AuthUtils> mocked = mockStatic(AuthUtils.class)) {
            mocked.when(() -> AuthUtils.extractUserId(any(HttpServletRequest.class), any(JwtUtil.class)))
                    .thenReturn(7L);

            ResponseEntity<?> resp = controller.stop(Map.of("req_id", "req-1"), request);

            assertEquals(200, resp.getStatusCode().value());
            verify(coreClient).treeHoleStop("req-1");
        }
    }
}
