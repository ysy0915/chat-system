package com.example.chat.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖：UA 过滤、敏感接口判断、客户端 IP 获取、限流、黑名单检查、自动拉黑
 */
@ExtendWith(MockitoExtension.class)
class IpRateLimitInterceptorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private IpRateLimitInterceptor interceptor;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        interceptor = new IpRateLimitInterceptor(redisTemplate);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ---------- getClientIp ----------

    @Test
    @DisplayName("X-Forwarded-For 优先取首段")
    void getClientIp_xForwardedForFirst() throws Exception {
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.10.11.12");
        request.addHeader("X-Real-IP", "99.99.99.99");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("1.2.3.4", invokeGetClientIp(request));
    }

    @Test
    @DisplayName("X-Forwarded-For 为 unknown 时退到 X-Real-IP")
    void getClientIp_xForwardedForUnknown_fallsBackToXRealIp() throws Exception {
        request.addHeader("X-Forwarded-For", "unknown");
        request.addHeader("X-Real-IP", "10.0.0.1");
        request.setRemoteAddr("127.0.0.1");

        assertEquals("10.0.0.1", invokeGetClientIp(request));
    }

    @Test
    @DisplayName("X-Real-IP 为 unknown 时退到 RemoteAddr")
    void getClientIp_xRealIpUnknown_fallsBackToRemoteAddr() throws Exception {
        request.addHeader("X-Real-IP", "unknown");
        request.setRemoteAddr("192.168.1.100");

        assertEquals("192.168.1.100", invokeGetClientIp(request));
    }

    @Test
    @DisplayName("无代理头时直接使用 RemoteAddr")
    void getClientIp_noProxyHeaders_usesRemoteAddr() throws Exception {
        request.setRemoteAddr("10.0.0.55");

        assertEquals("10.0.0.55", invokeGetClientIp(request));
    }

    // ---------- isBlockedUA ----------

    @Test
    @DisplayName("UA 为 null 时拦截")
    void isBlockedUA_nullUa_blocks() throws Exception {
        assertTrue(invokeIsBlockedUA((String) null));
    }

    @Test
    @DisplayName("UA 为空字符串时拦截")
    void isBlockedUA_blankUa_blocks() throws Exception {
        assertTrue(invokeIsBlockedUA("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Scrapy/2.0", "python-requests", "curl/7.0", "Wget/1.0",
            "Java/1.8", "Go-http-client", "PhantomJS", "HeadlessChrome", "spider-bot"})
    @DisplayName("爬虫 UA 关键词被拦截")
    void isBlockedUA_blockedKeywords_block(String ua) throws Exception {
        assertTrue(invokeIsBlockedUA(ua));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Googlebot/2.1", "Baiduspider", "bingbot", "Sogou", "360Spider", "Bytespider", "YandexBot"})
    @DisplayName("搜索引擎白名单 UA 放行")
    void isBlockedUA_allowedKeywords_allow(String ua) throws Exception {
        assertFalse(invokeIsBlockedUA(ua));
    }

    @Test
    @DisplayName("正常浏览器 UA 放行")
    void isBlockedUA_normalBrowser_allows() throws Exception {
        assertFalse(invokeIsBlockedUA("Mozilla/5.0 (Macintosh) Chrome/120.0"));
    }

    // ---------- isSensitiveEndpoint ----------

    @Test
    @DisplayName("登录接口是敏感接口")
    void isSensitiveEndpoint_login_isSensitive() throws Exception {
        assertTrue(invokeIsSensitiveEndpoint("/api/v1/auth/login"));
    }

    @Test
    @DisplayName("注册接口是敏感接口")
    void isSensitiveEndpoint_register_isSensitive() throws Exception {
        assertTrue(invokeIsSensitiveEndpoint("/api/v1/auth/register"));
    }

    @Test
    @DisplayName("普通聊天接口不是敏感接口")
    void isSensitiveEndpoint_chat_notSensitive() throws Exception {
        assertFalse(invokeIsSensitiveEndpoint("/api/v1/chat/send"));
    }

    // ---------- preHandle ----------

    @Test
    @DisplayName("正常请求通过所有检查，返回 true")
    void preHandle_normalRequest_allows() throws Exception {
        request.addHeader("User-Agent", "Mozilla/5.0 Chrome");
        request.setRequestURI("/api/v1/chat/send");
        request.setRemoteAddr("10.0.0.1");

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        assertTrue(interceptor.preHandle(request, response, null));
    }

    @Test
    @DisplayName("黑名单 IP 被拒绝返回 429")
    void preHandle_blacklisted_rejects() throws Exception {
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.setRequestURI("/api/v1/chat/send");
        request.setRemoteAddr("10.0.0.2");

        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(429, response.getStatus());
    }

    @Test
    @DisplayName("无 UA 的 API 请求被拒绝返回 403")
    void preHandle_noUaOnApi_rejects() throws Exception {
        request.setRequestURI("/api/v1/chat/send");
        request.setRemoteAddr("10.0.0.3");

        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    @DisplayName("超过全局限流时返回 429")
    void preHandle_exceedsGlobalRate_rejects() throws Exception {
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.setRequestURI("/api/v1/chat/send");
        request.setRemoteAddr("10.0.0.4");

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.increment(startsWith("ip:count:"))).thenReturn(1L);
        when(valueOperations.increment(startsWith("ip:rate:global:"))).thenReturn(61L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(429, response.getStatus());
    }

    @Test
    @DisplayName("超过敏感接口限流时返回 429")
    void preHandle_exceedsSensitiveRate_rejects() throws Exception {
        request.addHeader("User-Agent", "Mozilla/5.0");
        request.setRequestURI("/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.5");

        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(valueOperations.increment(startsWith("ip:count:"))).thenReturn(1L);
        when(valueOperations.increment(startsWith("ip:rate:sensitive:"))).thenReturn(6L);
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(429, response.getStatus());
    }

    // ---------- manualBlacklist / unblacklist / isIpBlocked ----------

    @Test
    @DisplayName("手动拉黑调用 Redis")
    void manualBlacklist_setsRedis() {
        interceptor.manualBlacklist("10.0.0.99", "测试拉黑", null);

        verify(valueOperations).set(eq("ip:blacklist:10.0.0.99"), eq("测试拉黑"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("解封 IP 清理 Redis key")
    void unblacklist_deletesKeys() {
        interceptor.unblacklist("10.0.0.99");

        verify(redisTemplate).delete("ip:blacklist:10.0.0.99");
        verify(redisTemplate).delete("ip:count:10.0.0.99");
    }

    @Test
    @DisplayName("isIpBlocked 委托给黑名单检查")
    void isIpBlocked_delegatesToBlacklist() {
        when(redisTemplate.hasKey("ip:blacklist:10.0.0.50")).thenReturn(true);
        assertTrue(interceptor.isIpBlocked("10.0.0.50"));
    }

    // ---------- Reflection helpers ----------

    private String invokeGetClientIp(HttpServletRequest req) throws Exception {
        var method = IpRateLimitInterceptor.class.getDeclaredMethod("getClientIp", HttpServletRequest.class);
        method.setAccessible(true);
        return (String) method.invoke(interceptor, req);
    }

    private boolean invokeIsBlockedUA(String ua) throws Exception {
        var method = IpRateLimitInterceptor.class.getDeclaredMethod("isBlockedUA", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(interceptor, ua);
    }

    private boolean invokeIsSensitiveEndpoint(String uri) throws Exception {
        var method = IpRateLimitInterceptor.class.getDeclaredMethod("isSensitiveEndpoint", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(interceptor, uri);
    }
}
