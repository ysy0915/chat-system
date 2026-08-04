package com.example.chat.config;

import com.example.chat.service.BroadcastService;
import com.example.chat.service.OnlineCountRedisService;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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

    private final ConcurrentHashMap<String, String> localSessions = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final OnlineCountRedisService onlineCountRedisService;
    private final BroadcastService broadcastService;

    public WebSocketSessionTracker(StringRedisTemplate redisTemplate,
                                   SimpMessagingTemplate messagingTemplate,
                                   OnlineCountRedisService onlineCountRedisService,
                                   BroadcastService broadcastService) {
        this.redisTemplate = redisTemplate;
        this.messagingTemplate = messagingTemplate;
        this.onlineCountRedisService = onlineCountRedisService;
        this.broadcastService = broadcastService;
        for (String page : DEFAULT_PAGES) {
            redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, page);
        }
    }

    public void registerUser(String sessionId, String userId, String name, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = normalizePage(page);

        redisTemplate.opsForSet().add(KNOWN_PAGES_KEY, pageKey);

        String previousPage = localSessions.get(sessionId);
        if (previousPage != null && !previousPage.equals(pageKey)) {
            removeSessionFromPage(sessionId, previousPage);
        }

        localSessions.put(sessionId, pageKey);
        redisTemplate.opsForSet().add(SESSION_PAGE_PREFIX + pageKey, sessionId);

        boolean isNewVisit = (previousPage == null || !previousPage.equals(pageKey));
        if (isNewVisit) {
            onlineCountRedisService.incrementVisitCount(pageKey, java.time.LocalDateTime.now());
        }

        broadcastPage(pageKey);
        broadcastAll();
    }

    public void unregisterUser(String sessionId, String page) {
        if (sessionId == null || sessionId.isBlank()) return;
        String pageKey = localSessions.remove(sessionId);
        if (pageKey == null) pageKey = normalizePage(page);
        removeSessionFromPage(sessionId, pageKey);
        broadcastPage(pageKey);
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
