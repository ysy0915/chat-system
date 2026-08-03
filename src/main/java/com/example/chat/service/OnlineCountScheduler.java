package com.example.chat.service;

import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

@Service
public class OnlineCountScheduler {

    private final WebSocketSessionTracker sessionTracker;
    private final OnlineCountRepository onlineCountRepository;

    public OnlineCountScheduler(WebSocketSessionTracker sessionTracker, OnlineCountRepository onlineCountRepository) {
        this.sessionTracker = sessionTracker;
        this.onlineCountRepository = onlineCountRepository;
    }

    @Scheduled(fixedRate = 60000)
    public void recordOnlineCounts() {
        Map<String, Integer> allCounts = sessionTracker.getAllCounts();
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, Integer> entry : allCounts.entrySet()) {
            OnlineCountRecord record = new OnlineCountRecord();
            record.page = entry.getKey();
            record.count = entry.getValue();
            record.recordedAt = now;
            try {
                onlineCountRepository.insert(record);
            } catch (Exception e) {
                System.err.println("[WARN] Failed to record online count for page=" + entry.getKey() + ": " + e.getMessage());
            }
        }
    }
}
