package com.example.chat.config;

import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketSessionTracker {

    private final AtomicInteger onlineCount = new AtomicInteger(0);
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketSessionTracker(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleConnect(SessionConnectedEvent event) {
        int count = onlineCount.incrementAndGet();
        broadcast(count);
    }

    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        int count = Math.max(0, onlineCount.decrementAndGet());
        broadcast(count);
    }

    private void broadcast(int count) {
        System.out.println("[Online] count=" + count);
        messagingTemplate.convertAndSend("/topic/online-count", Map.of("count", count));
    }

    public int getCount() {
        return onlineCount.get();
    }
}
