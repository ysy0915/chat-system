package com.example.chat.config;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionTracker {

    private final ConcurrentHashMap<String, String> onlineUsers = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketSessionTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void registerUser(String userId, String name) {
        onlineUsers.put(userId, name != null ? name : ("用户" + userId));
        broadcast();
    }

    public void unregisterUser(String userId) {
        onlineUsers.remove(userId);
        broadcast();
    }

    private void broadcast() {
        List<Map<String, String>> users = new ArrayList<>();
        for (Map.Entry<String, String> entry : onlineUsers.entrySet()) {
            users.add(Map.of("id", entry.getKey(), "name", entry.getValue()));
        }
        System.out.println("[Online] count=" + users.size() + ", users=" + users);
        messagingTemplate.convertAndSend("/topic/online-users", Map.of("count", users.size(), "users", users));
    }

    public int getCount() {
        return onlineUsers.size();
    }
}
