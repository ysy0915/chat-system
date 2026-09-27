package com.example.chat.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（防伪造）。
 *
 * <p>此前无条件采信 X-Forwarded-For / X-Real-IP，攻击者直连后端端口时可伪造
 * 这两个头，每次请求换一个"IP"，绕过登录失败锁定、注册限流与全站限流。</p>
 *
 * <p>修复策略：仅当 TCP 直连方是可信代理（回环/内网地址，即生产中 Nginx 与应用同机
 * 或同内网）时才采信代理头；直连方是外部地址时一律以 RemoteAddr 为准，忽略任何头。</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    /** 解析客户端真实 IP：可信代理场景取代理头，否则取 TCP 对端地址 */
    public static String resolve(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (!isTrustedProxy(remote)) {
            // 直连外部来源：不信任任何可伪造的代理头
            return remote;
        }
        String ip = header(request, "X-Forwarded-For");
        if (ip != null) {
            // X-Forwarded-For 可能有多段，取第一段（最初的客户端）——与原有优先级语义一致
            return ip.split(",")[0].trim();
        }
        ip = header(request, "X-Real-IP");
        return ip != null ? ip : remote;
    }

    private static String header(HttpServletRequest request, String name) {
        String v = request.getHeader(name);
        return (v == null || v.isBlank() || "unknown".equalsIgnoreCase(v)) ? null : v;
    }

    /** 是否可信代理：回环地址或 RFC1918/本地链路内网段（生产 Nginx 与应用同机/同内网） */
    static boolean isTrustedProxy(String addr) {
        if (addr == null || addr.isEmpty()) {
            return false;
        }
        if ("127.0.0.1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr) || "::1".equals(addr)) {
            return true;
        }
        if (addr.startsWith("10.") || addr.startsWith("192.168.")) {
            return true;
        }
        if (addr.startsWith("172.")) {
            int second = secondOctet(addr);
            return second >= 16 && second <= 31;   // 172.16.0.0 – 172.31.255.255
        }
        // IPv6 内网：fc00::/7（ULA）与 fe80::/10（链路本地）
        return addr.startsWith("fc") || addr.startsWith("fd") || addr.startsWith("fe80");
    }

    private static int secondOctet(String addr) {
        try {
            return Integer.parseInt(addr.split("\\.")[1]);
        } catch (Exception e) {
            return -1;
        }
    }
}
