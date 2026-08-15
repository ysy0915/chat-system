package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.OnlineCountRepository;
import com.example.chat.security.AdminAuthUtil;
import com.example.chat.service.OnlineCountRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MonitorController 真实行为断言：
 * 密码登录、在线历史/当前聚合、llm-stats 解析与 avgLatency 计算、traces 降级、快照落库。
 */
@ExtendWith(MockitoExtension.class)
class MonitorControllerTest {

    @Mock
    private OnlineCountRepository onlineCountRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private WebSocketSessionTracker sessionTracker;
    @Mock
    private OnlineCountRedisService onlineCountRedisService;
    @Mock
    private AdminAuthUtil adminAuthUtil;
    @Mock
    private CoreClient coreClient;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private MonitorController controller;

    @BeforeEach
    void setUp() {
        controller = new MonitorController(onlineCountRepository, messageRepository, sessionTracker,
                onlineCountRedisService, adminAuthUtil, coreClient);
        ReflectionTestUtils.setField(controller, "redisTemplate", redisTemplate);
    }

    @Test
    void login_wrongPassword_401() {
        when(adminAuthUtil.checkMonitorPassword("bad")).thenReturn(false);

        ResponseEntity<?> resp = controller.login(Map.of("password", "bad"));

        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void login_correctPassword_200() {
        when(adminAuthUtil.checkMonitorPassword("secret")).thenReturn(true);

        ResponseEntity<?> resp = controller.login(Map.of("password", "secret"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, ((Map<?, ?>) resp.getBody()).get("ok"));
    }

    @Test
    void getOnlineHistory_aggregatesAllSources() {
        when(sessionTracker.getAllRealCounts()).thenReturn(Map.of("global", 3));
        when(onlineCountRedisService.getDailyVisitCounts(any(LocalDateTime.class)))
                .thenReturn(Map.of("2026-08-13", 10));
        when(onlineCountRedisService.getPageDailyVisitCounts(any(LocalDateTime.class)))
                .thenReturn(Map.of());
        when(onlineCountRedisService.getHourlyPeakTotal()).thenReturn(99);
        when(onlineCountRedisService.getHourlyActiveCount()).thenReturn(12);

        ResponseEntity<?> resp = controller.getOnlineHistory(null, null);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(3, ((Map<?, ?>) body.get("current")).get("global"));
        assertEquals(10, ((Map<?, ?>) body.get("dailyVisits")).get("2026-08-13"));
        assertEquals(99, body.get("hourlyTotal"));
        assertEquals(12, body.get("hourlyActive"));
    }

    @Test
    void getCurrentCounts_returnsAllRealCounts() {
        when(sessionTracker.getAllRealCounts()).thenReturn(Map.of("global", 3, "home", 5));

        ResponseEntity<?> resp = controller.getCurrentCounts();

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(5, ((Map<?, ?>) resp.getBody()).get("home"));
    }

    @Test
    void getLlmStats_parsesHashValuesAndComputesAvgLatency() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries("llm:stats:2026-08-13"))
                .thenReturn(Map.<Object, Object>of("deepseek", "{\"total\":10,\"totalLatency\":5000}"));

        ResponseEntity<?> resp = controller.getLlmStats("2026-08-13");

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> deepseek = (Map<?, ?>) ((Map<?, ?>) resp.getBody()).get("deepseek");
        assertEquals(10L, deepseek.get("total"));
        assertEquals(500L, deepseek.get("avgLatency"));
    }

    @Test
    void getRecentTraces_success_returnsCorePayload() {
        when(coreClient.getRecentTraces(5)).thenReturn(Map.of("enabled", true, "traces", List.of("t1")));

        ResponseEntity<?> resp = controller.getRecentTraces(5);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(true, ((Map<?, ?>) resp.getBody()).get("enabled"));
    }

    @Test
    void getRecentTraces_coreFails_degradesGracefully() {
        when(coreClient.getRecentTraces(20)).thenThrow(new RuntimeException("core down"));

        ResponseEntity<?> resp = controller.getRecentTraces(null);

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(false, body.get("enabled"));
        assertEquals(0, ((List<?>) body.get("traces")).size());
    }

    @Test
    void getTotalUsage_returnsCompletedMessageCount() {
        // 累计使用量 = 已完成且有答案的对话消息数
        when(messageRepository.countAllWithAnswers()).thenReturn(10580);

        ResponseEntity<?> resp = controller.getTotalUsage();

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(10580L, ((Map<?, ?>) resp.getBody()).get("totalUsage"));
    }

    @Test
    void recordCurrentCounts_persistsSnapshotAndDb() {
        Map<String, Integer> counts = Map.of("global", 5);
        when(sessionTracker.getAllRealCounts()).thenReturn(counts);

        ResponseEntity<?> resp = controller.recordCurrentCounts();

        assertEquals(200, resp.getStatusCode().value());
        verify(onlineCountRedisService).recordSnapshot(eq(counts), any(LocalDateTime.class));
        verify(onlineCountRepository).insert(any());
    }
}
