package com.example.chat.service;

import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class OnlineCountScheduler {

    private static final Logger log = LoggerFactory.getLogger(OnlineCountScheduler.class);

    private final WebSocketSessionTracker sessionTracker;
    private final OnlineCountRepository onlineCountRepository;
    private final OnlineCountRedisService onlineCountRedisService;

    public OnlineCountScheduler(WebSocketSessionTracker sessionTracker,
                                OnlineCountRepository onlineCountRepository,
                                OnlineCountRedisService onlineCountRedisService) {
        this.sessionTracker = sessionTracker;
        this.onlineCountRepository = onlineCountRepository;
        this.onlineCountRedisService = onlineCountRedisService;
    }

    @Scheduled(fixedRate = 60000)
    public void recordOnlineCounts() {
        Map<String, Integer> allCounts = sessionTracker.getAllCounts();
        LocalDateTime now = LocalDateTime.now();
        onlineCountRedisService.recordSnapshot(allCounts, now);
        for (Map.Entry<String, Integer> entry : allCounts.entrySet()) {
            OnlineCountRecord record = new OnlineCountRecord();
            record.page = entry.getKey();
            record.count = entry.getValue();
            record.recordedAt = now;
            try {
                onlineCountRepository.insert(record);
            } catch (DataAccessException e) {
                log.warn("[WARN] Failed to record online count for page={}: {}", entry.getKey(), e.getMessage());
            }
        }
    }
}
