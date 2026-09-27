package com.example.chat.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ClientIpResolver 行为断言：外部直连不信任代理头（防伪造绕过限流），
 * 可信代理（Nginx 同机/内网）采信 X-Real-IP / X-Forwarded-For。
 */
@ExtendWith(MockitoExtension.class)
class ClientIpResolverTest {

    @Mock
    private HttpServletRequest request;

    private HttpServletRequest remote(String remoteAddr) {
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }

    @Test
    @DisplayName("外部直连：忽略伪造的 X-Real-IP，返回 TCP 对端地址")
    void externalDirect_forgedHeaderIgnored() {
        lenient().when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
        when(request.getRemoteAddr()).thenReturn("203.0.113.9");

        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("外部直连：忽略伪造的 X-Forwarded-For")
    void externalDirect_forgedXffIgnored() {
        lenient().when(request.getHeader("X-Real-IP")).thenReturn(null);
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn("8.8.8.8, 9.9.9.9");
        when(request.getRemoteAddr()).thenReturn("198.51.100.7");

        assertEquals("198.51.100.7", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("可信代理（本机 Nginx）：采信 X-Real-IP")
    void trustedProxy_usesRealIpHeader() {
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.9");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("可信代理：X-Forwarded-For 优先，取首段")
    void trustedProxy_usesFirstXffSegment() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.9, 10.0.0.1");
        lenient().when(request.getHeader("X-Real-IP")).thenReturn("10.9.9.9");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("203.0.113.9", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("可信代理无代理头：回退 TCP 对端地址")
    void trustedProxy_noHeaders_fallsBackToRemote() {
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("X-Forwarded-For 值为 unknown：视为无值")
    void unknownHeaderTreatedAsEmpty() {
        when(request.getHeader("X-Real-IP")).thenReturn("unknown");
        when(request.getHeader("X-Forwarded-For")).thenReturn("unknown");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", ClientIpResolver.resolve(request));
    }

    @Test
    @DisplayName("可信代理判定：回环/10段/192.168段/172.16-31段/IPv6内网")
    void trustedProxyRanges() {
        assertTrue(ClientIpResolver.isTrustedProxy("127.0.0.1"));
        assertTrue(ClientIpResolver.isTrustedProxy("::1"));
        assertTrue(ClientIpResolver.isTrustedProxy("10.1.2.3"));
        assertTrue(ClientIpResolver.isTrustedProxy("192.168.0.5"));
        assertTrue(ClientIpResolver.isTrustedProxy("172.16.0.1"));
        assertTrue(ClientIpResolver.isTrustedProxy("172.31.255.255"));
        assertTrue(ClientIpResolver.isTrustedProxy("fe80::1"));
        assertFalse(ClientIpResolver.isTrustedProxy("172.32.0.1"));
        assertFalse(ClientIpResolver.isTrustedProxy("172.15.0.1"));
        assertFalse(ClientIpResolver.isTrustedProxy("8.8.8.8"));
        assertFalse(ClientIpResolver.isTrustedProxy(null));
    }
}
