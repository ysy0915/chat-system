package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ContentSafetyService;
import com.example.chat.service.OnlineCountRedisService;
import com.example.chat.service.RateLimitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MessageController 真实行为断言：
 * 限流 429、敏感词 400、新用户自动创建、ai_answer 透传模型配置、regenerate/stop 守卫与转发、在线人数聚合。
 */
@ExtendWith(MockitoExtension.class)
class MessageControllerTest {

    @Mock
    private CoreClient coreClient;
    @Mock
    private SimpMessagingTemplate simpMessagingTemplate;
    @Mock
    private WebSocketSessionTracker sessionTracker;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private ContentSafetyService contentSafetyService;
    @Mock
    private BroadcastService broadcastService;
    @Mock
    private OnlineCountRedisService onlineCountRedisService;

    private MessageController controller;

    /** 模拟已登录用户：JWT 安全上下文（credentials=uid），配合 AuthUtils.extractUserIdFromContext */
    private static void authenticateAs(long uid) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user", uid));
    }

    @BeforeEach
    void setUp() {
        controller = new MessageController(coreClient, sessionTracker,
                rateLimitService, contentSafetyService, broadcastService, onlineCountRedisService,
                new ObjectMapper());
    }

    @Test
    void createMessage_rateLimited_429WithRetryAfter() {
        authenticateAs(1L);
        when(rateLimitService.isAllowed(1L)).thenReturn(false);
        when(rateLimitService.getRemainingSeconds(1L)).thenReturn(15L);

        ResponseEntity<?> resp = controller.createMessage(Map.of("user_id", 1L, "question", "hi"));

        assertEquals(429, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(15L, body.get("retry_after"));
    }

    @Test
    void createMessage_sensitiveContent_400() {
        authenticateAs(1L);
        when(rateLimitService.isAllowed(1L)).thenReturn(true);
        when(contentSafetyService.detectSensitive("暴力内容")).thenReturn("violence");
        when(contentSafetyService.getLabelHint("violence")).thenReturn("问题包含暴力内容，请修改后重试");

        ResponseEntity<?> resp = controller.createMessage(Map.of("user_id", 1L, "question", "暴力内容"));

        assertEquals(400, resp.getStatusCode().value());
        verify(coreClient, never()).insertMessage(any());
    }

    @Test
    void createMessage_newUser_createsAndInserts() {
        authenticateAs(0L);
        when(rateLimitService.isAllowed(0L)).thenReturn(true);
        when(contentSafetyService.detectSensitive("你好")).thenReturn(null);
        when(coreClient.getUserById(0L)).thenReturn(null);
        when(coreClient.getUserByEmail("user_0@chat.local"))
                .thenReturn(null)
                .thenReturn(Map.of("id", 42L, "name", "", "nickname", "用户0", "role", "user",
                        "email", "user_0@chat.local"));
        when(coreClient.insertUser(any())).thenReturn(null);

        ResponseEntity<?> resp = controller.createMessage(Map.of("question", "你好"));

        assertEquals(202, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(42L, body.get("user_id"));
        ArgumentCaptor<Map<String, Object>> msgCaptor = ArgumentCaptor.forClass(Map.class);
        verify(coreClient).insertMessage(msgCaptor.capture());
        assertEquals(42L, msgCaptor.getValue().get("userId"));
        assertEquals("queued", msgCaptor.getValue().get("status"));
        verify(broadcastService).broadcast(anyString(), any());
        verify(coreClient, never()).chatProcess(any());
    }

    @Test
    void createMessage_aiAnswerTrue_forwardsModelConfig() {
        authenticateAs(1L);
        when(rateLimitService.isAllowed(1L)).thenReturn(true);
        when(contentSafetyService.detectSensitive("hi")).thenReturn(null);
        when(coreClient.getUserById(1L))
                .thenReturn(Map.of("id", 1L, "name", "alice", "nickname", "小爱", "role", "user", "email", "a@x.com"));

        controller.createMessage(Map.of("user_id", 1L, "question", "hi",
                "ai_answer", "true", "preferred_model_config_id", 7));

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(coreClient).chatProcess(payload.capture());
        assertEquals("hi", payload.getValue().get("question"));
        assertEquals(7, payload.getValue().get("preferred_model_config_id"));
        assertEquals("false", payload.getValue().get("private"));
    }

    @Test
    void regenerate_blankReqId_400() {
        ResponseEntity<?> resp = controller.regenerate(Map.of("req_id", "  ", "user_id", 1L));

        assertEquals(400, resp.getStatusCode().value());
        verify(coreClient, never()).chatRegenerate(anyString(), any());
    }

    @Test
    void regenerate_success_callsChatRegenerate() {
        authenticateAs(1L);
        ResponseEntity<?> resp = controller.regenerate(Map.of("req_id", "req-1", "user_id", 1L));

        assertEquals(202, resp.getStatusCode().value());
        verify(coreClient).chatRegenerate("req-1", 1L);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("regenerating", body.get("status"));
    }

    @Test
    void stop_blankReqId_400() {
        ResponseEntity<?> resp = controller.stop(Map.of("req_id", ""));

        assertEquals(400, resp.getStatusCode().value());
        verify(coreClient, never()).chatStop(anyString());
    }

    @Test
    void stop_success_callsChatStop() {
        ResponseEntity<?> resp = controller.stop(Map.of("req_id", "req-9"));

        assertEquals(200, resp.getStatusCode().value());
        verify(coreClient).chatStop("req-9");
    }

    @Test
    void getOnlineCount_combinesSessionCountAndHourlyActive() {
        when(sessionTracker.getCount("global")).thenReturn(3);
        when(onlineCountRedisService.getHourlyActiveCount()).thenReturn(5);

        ResponseEntity<?> resp = controller.getOnlineCount("global");

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(3, body.get("count"));
        assertEquals(5, body.get("hourlyActive"));
    }
}
