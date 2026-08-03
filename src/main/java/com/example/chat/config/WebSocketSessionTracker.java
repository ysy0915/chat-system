package com.example.chat.config;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionTracker {

    private final ConcurrentHashMap<String, Set<String>> pageUsers = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketSessionTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void registerUser(String userId, String name, String page) {
        String pageKey = (page != null && !page.isBlank()) ? page : "global";
        pageUsers.computeIfAbsent(pageKey, k -> ConcurrentHashMap.newKeySet()).add(userId);
        broadcastPage(pageKey);
    }

    public void unregisterUser(String userId, String page) {
        String pageKey = (page != null && !page.isBlank()) ? page : "global";
        Set<String> users = pageUsers.get(pageKey);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) {
                pageUsers.remove(pageKey);
            }
        }
        broadcastPage(pageKey);
    }

    private void broadcastPage(String pageKey) {
        Set<String> users = pageUsers.getOrDefault(pageKey, Collections.emptySet());
        int count = users.size();
        messagingTemplate.convertAndSend("/topic/online-count/" + pageKey,
                Map.of("count", count, "page", pageKey));
    }

    public int getCount(String page) {
        Set<String> users = pageUsers.get(page);
        return users != null ? users.size() : 0;
    }

    public int getTotalCount() {
        return pageUsers.values().stream().mapToInt(Set::size).sum();
    }

    public Map<String, Integer> getAllCounts() {
        Map<String, Integer> result = new java.util.HashMap<>();
        pageUsers.forEach((page, users) -> result.put(page, users.size()));
        return result;
    }
}
