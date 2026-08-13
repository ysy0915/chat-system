package com.example.chat.controller;

import com.example.chat.security.AdminAuthUtil;
import com.example.chat.security.IpRateLimitInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IpAdminController 真实行为断言：
 * 管理员鉴权 401、黑名单查询解析、手动拉黑/解封委托拦截器、IP 统计聚合。
 */
@ExtendWith(MockitoExtension.class)
class IpAdminControllerTest {

    @Mock
    private IpRateLimitInterceptor interceptor;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private AdminAuthUtil adminAuthUtil;
    @Mock
    private ValueOperations<String, String> valueOps;

    private IpAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new IpAdminController(interceptor, redis, adminAuthUtil);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void listBlacklist_wrongPassword_401() {
        when(adminAuthUtil.checkMonitorPassword("bad")).thenReturn(false);

        ResponseEntity<?> resp = controller.listBlacklist("bad");

        assertEquals(401, resp.getStatusCode().value());
    }

    @Test
    void listBlacklist_authorized_parsesIpReasonTtl() {
        when(adminAuthUtil.checkMonitorPassword("secret")).thenReturn(true);
        when(redis.keys("ip:blacklist:*")).thenReturn(Set.of("ip:blacklist:1.2.3.4"));
        when(valueOps.get("ip:blacklist:1.2.3.4")).thenReturn("爬虫");
        when(redis.getExpire("ip:blacklist:1.2.3.4")).thenReturn(600L);

        ResponseEntity<?> resp = controller.listBlacklist("secret");

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("爬虫 | TTL: 600s", body.get("1.2.3.4"));
    }

    @Test
    void blacklist_authorized_callsInterceptorWithReasonAndMinutes() {
        when(adminAuthUtil.checkMonitorPassword("secret")).thenReturn(true);

        ResponseEntity<?> resp = controller.blacklist("1.2.3.4", "secret",
                Map.of("reason", "恶意攻击", "minutes", 30));

        assertEquals(200, resp.getStatusCode().value());
        verify(interceptor).manualBlacklist("1.2.3.4", "恶意攻击", Duration.ofMinutes(30));
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(30, body.get("minutes"));
    }

    @Test
    void blacklist_wrongPassword_401() {
        when(adminAuthUtil.checkMonitorPassword("bad")).thenReturn(false);

        ResponseEntity<?> resp = controller.blacklist("1.2.3.4", "bad", Map.of());

        assertEquals(401, resp.getStatusCode().value());
        verify(interceptor, never()).manualBlacklist(any(), any(), any());
    }

    @Test
    void unblacklist_authorized_callsInterceptor() {
        when(adminAuthUtil.checkMonitorPassword("secret")).thenReturn(true);

        ResponseEntity<?> resp = controller.unblacklist("1.2.3.4", "secret");

        assertEquals(200, resp.getStatusCode().value());
        verify(interceptor).unblacklist("1.2.3.4");
    }

    @Test
    void ipStats_authorized_aggregatesBlockedAndCounts() {
        when(adminAuthUtil.checkMonitorPassword("secret")).thenReturn(true);
        when(interceptor.isIpBlocked("1.2.3.4")).thenReturn(true);
        when(valueOps.get("ip:count:1.2.3.4")).thenReturn("42");
        when(valueOps.get("ip:rate:global:1.2.3.4")).thenReturn("7");

        ResponseEntity<?> resp = controller.ipStats("1.2.3.4", "secret");

        assertEquals(200, resp.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals(true, body.get("blocked"));
        assertEquals(42L, body.get("requestCount60s"));
        assertEquals(7L, body.get("globalRate60s"));
    }
}
