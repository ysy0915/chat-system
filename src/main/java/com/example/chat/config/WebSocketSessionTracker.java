package com.example.chat.config;

import com.example.chat.service.BroadcastService;
import com.example.chat.service.OnlineCountRedisService;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionTracker {

    private static final Set<String> DEFAULT_PAGES = Set.of(
            "landing", "chat", "personal", "debate", "games", "pingpong", "snakeking",
            "castlesiege", "history", "graph", "about", "profile", "admin-models",
            "sql", "monitor", "media", "global"
    );

    private static final String SESSION_PAGE_PREFIX = "ws:page:";
    private static final String KNOWN_PAGES_KEY = "ws:known:pages";
    private static final String SESSION_HEARTBEAT_PREFIX = "ws:heartbeat:";
    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000; // 5 分钟无操作清理

    private final ConcurrentHashMap<String, String> localSessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final OnlineCountRedisService onlineCountRedisService;
    private final BroadcastService broadcastService;

    public WebSocketSessionTracker(StringRedisTemplate redisTemplate,
                                   OnlineCountRedisService onlineCountRedisService,
                                   BroadcastService broadcastService) {
        this.redisTemplate = redisTemplate;
        this.onlineCountRedisService = onlineCountRedisService;
        this.broadcastService = broadcastService;
        for (String page : DEFAULT_PAGES) {
            redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, page);
        }
    }

    /** 记录 session 心跳时间（在 register 和心跳时调用） */
    public void touchSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.opsForValue().set(SESSION_HEARTBEAT_PREFIX + sessionId,
                String.valueOf(System.currentTimeMillis()),
                10, java.util.concurrent.TimeUnit.MINUTES);
    }

    /** 定时清理超时 session（由 @Scheduled 调用） */
    public void cleanupIdleSessions() {
        Set<String> knownPages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (knownPages == null) return;
        long now = System.currentTimeMillis();
        for (String page : knownPages) {
            Set<String> sessionIds = redisTemplate.opsForSet().members(SESSION_PAGE_PREFIX + page);
            if (sessionIds == null) continue;
            for (String sid : sessionIds) {
                String hb = redisTemplate.opsForValue().get(SESSION_HEARTBEAT_PREFIX + sid);
                long lastActive = (hb != null) ? Long.parseLong(hb) : 0;
                if (now - lastActive > IDLE_TIMEOUT_MS) {
                    // 超时未活动，清理
                    unregisterUser(sid, page);
                    redisTemplate.delete(SESSION_HEARTBEAT_PREFIX + sid);
                }
            }
        }
    }

    public void registerUser(String sessionId, String userId, String name, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = normalizePage(page);

        redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, pageKey);

        // 清理该 sessionId 在所有 page 的残留（防止 unregister 消息丢失导致只增不减）
        String previousPage = localSessions.get(sessionId);
        if (previousPage != null && !previousPage.equals(pageKey)) {
            removeSessionFromPage(sessionId, previousPage);
        }
        // 兜底：扫描所有已知 page，移除可能残留的 sessionId
        Set<String> knownPages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (knownPages != null) {
            for (String p : knownPages) {
                if (!p.equals(pageKey)) {
                    redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + p, sessionId);
                }
            }
        }

        localSessions.put(sessionId, pageKey);
        redisTemplate.opsForSet().add(SESSION_PAGE_PREFIX + pageKey, sessionId);
        touchSession(sessionId); // 更新心跳时间

        boolean isNewVisit = (previousPage == null || !previousPage.equals(pageKey));
        if (isNewVisit) {
            onlineCountRedisService.incrementVisitCount(pageKey, java.time.LocalDateTime.now());
        }

        broadcastPage(pageKey);
        broadcastAll();
    }

    public void unregisterUser(String sessionId, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = (page != null && !page.isBlank()) ? normalizePage(page) : localSessions.get(sessionId);
        localSessions.remove(sessionId);
        redisTemplate.delete(SESSION_HEARTBEAT_PREFIX + sessionId);
        if (pageKey != null) {
            removeSessionFromPage(sessionId, pageKey);
            broadcastPage(pageKey);
        }
        broadcastAll();
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event == null) return;
        unregisterUser(event.getSessionId(), null);
    }

    public int getCount(String page) {
        Long size = redisTemplate.opsForSet().size(SESSION_PAGE_PREFIX + normalizePage(page));
        return size != null ? size.intValue() : 0;
    }

    public int getTotalCount() {
        return getAllCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> getAllCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> pages = redisTemplate.opsForSet().members(KNOWN_PAGES_KEY);
        if (pages != null) {
            for (String page : pages) {
                result.put(page, getCount(page));
            }
        }
        return result;
    }

    private void removeSessionFromPage(String sessionId, String pageKey) {
        redisTemplate.opsForSet().remove(SESSION_PAGE_PREFIX + pageKey, sessionId);
    }

    private void broadcastPage(String pageKey) {
        int count = getCount(pageKey);
        broadcastService.broadcast("/topic/online-count/" + pageKey,
                Map.of("count", count, "page", pageKey));
    }

    private void broadcastAll() {
        broadcastService.broadcast("/topic/online-count/all",
                Map.of("total", getTotalCount(), "pages", getAllCounts()));
    }

    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }
}
