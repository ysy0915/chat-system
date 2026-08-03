package com.example.chat.config;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionTracker {

    private static final Set<String> DEFAULT_PAGES = Set.of(
            "landing", "chat", "personal", "debate", "games", "pingpong", "snakeking",
            "castlesiege", "history", "graph", "about", "profile", "admin-models",
            "sql", "monitor", "media", "global"
    );

    private final ConcurrentHashMap<String, SessionPresence> sessionPresence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> pageSessions = new ConcurrentHashMap<>();
    private final Set<String> knownPages = ConcurrentHashMap.newKeySet();
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketSessionTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
        this.knownPages.addAll(DEFAULT_PAGES);
    }

    public void registerUser(String sessionId, String userId, String name, String page) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String pageKey = normalizePage(page);
        String safeUserId = userId == null || userId.isBlank() ? sessionId : userId;
        knownPages.add(pageKey);

        SessionPresence previous = sessionPresence.put(sessionId, new SessionPresence(safeUserId, defaultName(name, safeUserId), pageKey));
        if (previous != null && !previous.page().equals(pageKey)) {
            removeSessionFromPage(sessionId, previous.page());
            broadcastPage(previous.page());
        }

        pageSessions.computeIfAbsent(pageKey, key -> ConcurrentHashMap.newKeySet()).add(sessionId);
        broadcastPage(pageKey);
        broadcastAll();
    }

    public void unregisterUser(String sessionId, String page) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SessionPresence removed = sessionPresence.remove(sessionId);
        String pageKey = removed != null ? removed.page() : normalizePage(page);
        removeSessionFromPage(sessionId, pageKey);
        broadcastPage(pageKey);
        broadcastAll();
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        if (event == null) {
            return;
        }
        unregisterUser(event.getSessionId(), null);
    }

    public int getCount(String page) {
        return pageSessions.getOrDefault(normalizePage(page), Collections.emptySet()).size();
    }

    public int getTotalCount() {
        return getAllCounts().values().stream().mapToInt(Integer::intValue).sum();
    }

    public Map<String, Integer> getAllCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<String> pages = new LinkedHashSet<>(knownPages);
        pageSessions.forEach((page, sessions) -> pages.add(page));
        for (String page : pages) {
            result.put(page, pageSessions.getOrDefault(page, Collections.emptySet()).size());
        }
        return result;
    }

    public Set<String> getKnownPages() {
        return new LinkedHashSet<>(knownPages);
    }

    private void removeSessionFromPage(String sessionId, String pageKey) {
        Set<String> sessions = pageSessions.get(pageKey);
        if (sessions == null) {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            pageSessions.remove(pageKey);
        }
    }

    private void broadcastPage(String pageKey) {
        int count = getCount(pageKey);
        messagingTemplate.convertAndSend("/topic/online-count/" + pageKey,
                Map.of("count", count, "page", pageKey));
    }

    private void broadcastAll() {
        messagingTemplate.convertAndSend("/topic/online-count/all",
                Map.of(
                        "total", getTotalCount(),
                        "pages", getAllCounts()
                ));
    }

    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }

    private String defaultName(String name, String userId) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return "用户" + userId;
    }

    private record SessionPresence(String userId, String name, String page) {
    }
}
