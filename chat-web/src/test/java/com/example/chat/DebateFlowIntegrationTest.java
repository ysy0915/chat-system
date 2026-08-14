package com.example.chat;

import com.example.chat.client.CoreClient;
import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.security.JwtUtil;
import com.example.chat.security.RateLimitChecker;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ContentSafetyService;
import com.example.chat.service.OnlineCountRedisService;
import com.example.chat.service.OnlineCountScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 辩论链路集成测试 — 完整 Spring 上下文（WebApplication + H2 + 真实内嵌 Tomcat + 外部 IO mock）。
 *
 * <p>覆盖 DebateController 的完整路径：内容安全检测 → insertDebateRecord → insertMessage
 * → debateStart（含 rounds 1-10 钳制与 mode 透传）→ 统一 202 响应。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WebApplication.class)
@ActiveProfiles("test")
@DisplayName("辩论链路集成测试（@SpringBootTest 真实 HTTP 栈）")
class DebateFlowIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private CoreClient coreClient;

    @MockBean
    private RateLimitChecker rateLimitChecker;

    @MockBean
    private ContentSafetyService contentSafetyService;

    @MockBean
    private BroadcastService broadcastService;

    @MockBean
    private WebSocketSessionTracker sessionTracker;

    @MockBean
    private OnlineCountRedisService onlineCountRedisService;

    @MockBean
    private OnlineCountScheduler onlineCountScheduler;

    @BeforeEach
    void setUp() {
        // 用户限流放行（key 前缀 rate:user:）
        when(rateLimitChecker.checkAndIncrement(startsWith("rate:user:"), anyInt(), any(Duration.class))).thenReturn(true);
        // IP 全局限流放行（key 前缀 ip:rate:，IpRateLimitInterceptor 使用）
        when(rateLimitChecker.checkAndIncrement(startsWith("ip:rate:"), anyInt(), any(Duration.class))).thenReturn(true);
        // 内容安全默认通过
        when(contentSafetyService.detectSensitive(any())).thenReturn(null);
        // 命中敏感词时返回具体提示（避免 mock 返回 null 触发 Map.of NPE）
        when(contentSafetyService.getLabelHint(anyString())).thenReturn("问题包含不适当内容，请修改后重试");
    }

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0 Safari/537.36";

    /** 生成已登录用户(uid=1)的测试 JWT（test profile 下 JwtUtil 使用随机密钥） */
    private String bearerToken() {
        return "Bearer " + jwtUtil.generateToken("test@chat.local", 1L, "USER");
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.USER_AGENT, BROWSER_UA);
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken());
        return restTemplate.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private int roundsOf(Map<String, Object> payload) {
        return ((Number) payload.get("rounds")).intValue();
    }

    @Test
    @DisplayName("POST /api/v1/debate 全链路：安全检测→插入记录→触发 AI 辩论")
    void startDebate_fullFlow_returns202() throws Exception {
        ResponseEntity<String> resp = post("/api/v1/debate",
                "{\"question\":\"AI 是否安全\",\"user_id\":1}");

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("debating", body.get("status").asText());
        assertTrue(body.get("req_id").asText().length() > 0);

        verify(coreClient).insertDebateRecord(any());
        verify(coreClient).insertMessage(any());
        verify(coreClient).debateStart(any());
    }

    @Test
    @DisplayName("POST /api/v1/debate 命中敏感词返回 400 且不触发辩论")
    void startDebate_sensitiveContent_returns400() throws Exception {
        when(contentSafetyService.detectSensitive("违规内容")).thenReturn("pornography");

        ResponseEntity<String> resp = post("/api/v1/debate",
                "{\"question\":\"违规内容\",\"user_id\":1}");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("问题包含不适当内容，请修改后重试", body.get("error").asText());
        verify(coreClient, never()).insertDebateRecord(any());
        verify(coreClient, never()).insertMessage(any());
        verify(coreClient, never()).debateStart(any());
    }

    @Test
    @DisplayName("POST /api/v1/debate rounds 钳制 1-10（20→10、0→1、非法→默认3）")
    void startDebate_roundsClampedToOneToTen() throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        post("/api/v1/debate", "{\"question\":\"q\",\"user_id\":1,\"rounds\":20}");
        post("/api/v1/debate", "{\"question\":\"q\",\"user_id\":1,\"rounds\":0}");
        post("/api/v1/debate", "{\"question\":\"q\",\"user_id\":1,\"rounds\":\"abc\"}");

        verify(coreClient, times(3)).debateStart(captor.capture());
        assertEquals(10, roundsOf(captor.getAllValues().get(0)));
        assertEquals(1, roundsOf(captor.getAllValues().get(1)));
        assertEquals(3, roundsOf(captor.getAllValues().get(2)));
    }

    @Test
    @DisplayName("POST /api/v1/debate mode 透传给 core（缺省为空串）")
    void startDebate_modeTransmittedToCore() throws Exception {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);

        post("/api/v1/debate", "{\"question\":\"q\",\"user_id\":1,\"mode\":\"tree\"}");
        verify(coreClient).debateStart(captor.capture());
        assertEquals("tree", captor.getValue().get("mode"));

        post("/api/v1/debate", "{\"question\":\"q\",\"user_id\":1}");
        verify(coreClient, times(2)).debateStart(captor.capture());
        assertEquals("", captor.getValue().get("mode"));
    }
}
