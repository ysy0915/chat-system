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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息链路集成测试 — 完整 Spring 上下文（WebApplication + H2 + 真实内嵌 Tomcat + 外部 IO mock）。
 *
 * <p>RANDOM_PORT 真实 HTTP 栈：Servlet 容器 → Security 链 → Controller → Service
 * （限流/内容安全/广播）→ CoreClient（mock 下游）→ Jackson 序列化 → 统一响应。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = WebApplication.class)
@ActiveProfiles("test")
@DisplayName("消息链路集成测试（@SpringBootTest 真实 HTTP 栈）")
class MessageFlowIntegrationTest {

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
        // 用户限流放行（key 前缀 rate:user:，真实 RateLimitService 逻辑保留，Redis 底层 mock）
        when(rateLimitChecker.checkAndIncrement(startsWith("rate:user:"), anyInt(), any(Duration.class))).thenReturn(true);
        // IP 全局限流放行（key 前缀 ip:rate:，IpRateLimitInterceptor 使用）
        when(rateLimitChecker.checkAndIncrement(startsWith("ip:rate:"), anyInt(), any(Duration.class))).thenReturn(true);
        // 内容安全默认通过
        when(contentSafetyService.detectSensitive(any())).thenReturn(null);
        // 命中敏感词时返回具体提示（避免 mock 返回 null 触发 Map.of NPE）
        when(contentSafetyService.getLabelHint(anyString())).thenReturn("问题包含不适当内容，请修改后重试");
        // 用户查询默认命中已有用户
        when(coreClient.getUserById(1L)).thenReturn(Map.of("id", 1, "nickname", "测试用户"));
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

    private ResponseEntity<String> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, BROWSER_UA);
        headers.set(HttpHeaders.AUTHORIZATION, bearerToken());
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("POST /api/v1/messages 全链路：限流→用户→落库→广播→AI 处理")
    void createMessage_fullFlow_returns202AndDelegatesToCore() throws Exception {
        ResponseEntity<String> resp = post("/api/v1/messages",
                "{\"question\":\"你好世界\",\"user_id\":1,\"ai_answer\":\"true\"}");

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals("queued", body.get("status").asText());
        assertTrue(body.get("req_id").asText().length() > 0);
        assertEquals(1, body.get("user_id").asInt());

        verify(coreClient).insertMessage(any());
        verify(coreClient).chatProcess(any());
        verify(broadcastService).broadcast(eq("/topic/public-questions"), any());
    }

    @Test
    @DisplayName("POST /api/v1/messages ai_answer=false 不触发 AI 处理")
    void createMessage_noAiAnswer_skipsChatProcess() throws Exception {
        ResponseEntity<String> resp = post("/api/v1/messages",
                "{\"question\":\"你好\",\"user_id\":1,\"ai_answer\":\"false\"}");

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        verify(coreClient).insertMessage(any());
        verify(coreClient, never()).chatProcess(any());
    }

    @Test
    @DisplayName("POST /api/v1/messages 限流命中返回 429 + retry_after")
    void createMessage_rateLimited_returns429() throws Exception {
        // 仅用户限流命中（拦截器的 ip:rate: 前缀不受影响）
        when(rateLimitChecker.checkAndIncrement(startsWith("rate:user:"), anyInt(), any(Duration.class))).thenReturn(false);
        when(rateLimitChecker.getRemainingSeconds(startsWith("rate:user:"), eq(60L))).thenReturn(15L);

        ResponseEntity<String> resp = post("/api/v1/messages",
                "{\"question\":\"你好\",\"user_id\":1}");

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.getStatusCode());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals(15, body.get("retry_after").asInt());
        verify(coreClient, never()).insertMessage(any());
    }

    @Test
    @DisplayName("POST /api/v1/messages 命中敏感词返回 400")
    void createMessage_sensitiveContent_returns400() throws Exception {
        when(contentSafetyService.detectSensitive("违规内容")).thenReturn("pornography");

        ResponseEntity<String> resp = post("/api/v1/messages",
                "{\"question\":\"违规内容\",\"user_id\":1}");

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(coreClient, never()).insertMessage(any());
    }

    @Test
    @DisplayName("GET /api/v1/messages 消息列表透传 core")
    void listMessages_delegatesToCore() {
        when(coreClient.listMessages(1L)).thenReturn(List.of(
                Map.of("id", 1, "question", "你好", "status", "done")));

        ResponseEntity<String> resp = get("/api/v1/messages?user_id=1");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("\"question\":\"你好\""));
    }

    @Test
    @DisplayName("GET /api/v1/messages/online-count 在线人数")
    void onlineCount_returnsAggregated() throws Exception {
        when(sessionTracker.getCount("global")).thenReturn(42);
        when(onlineCountRedisService.getHourlyActiveCount()).thenReturn(7);

        ResponseEntity<String> resp = get("/api/v1/messages/online-count?page=global");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertEquals(42, body.get("count").asInt());
        assertEquals(7, body.get("hourlyActive").asInt());
    }
}
