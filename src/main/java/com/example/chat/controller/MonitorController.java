package com.example.chat.controller;

import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private final OnlineCountRepository onlineCountRepository;
    private final WebSocketSessionTracker sessionTracker;

    public MonitorController(OnlineCountRepository onlineCountRepository, WebSocketSessionTracker sessionTracker) {
        this.onlineCountRepository = onlineCountRepository;
        this.sessionTracker = sessionTracker;
    }

    @GetMapping("/online-history")
    public ResponseEntity<?> getOnlineHistory(@RequestParam(value = "minutes", defaultValue = "360") int minutes) {
        LocalDateTime since = LocalDateTime.now().minusMinutes(minutes);
        List<OnlineCountRecord> records = onlineCountRepository.findRecent(since.toString().substring(0, 19).replace('T', ' '));

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (OnlineCountRecord r : records) {
            grouped.computeIfAbsent(r.page, k -> new ArrayList<>())
                    .add(Map.of(
                            "time", r.recordedAt != null ? r.recordedAt.toString() : "",
                            "count", r.count
                    ));
        }

        Map<String, Integer> currentCounts = sessionTracker.getAllCounts();

        return ResponseEntity.ok(Map.of(
                "history", grouped,
                "current", currentCounts
        ));
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentCounts() {
        return ResponseEntity.ok(sessionTracker.getAllCounts());
    }

    @PostMapping("/record")
    public ResponseEntity<?> recordCurrentCounts() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> allCounts = sessionTracker.getAllCounts();
        for (Map.Entry<String, Integer> entry : allCounts.entrySet()) {
            OnlineCountRecord record = new OnlineCountRecord();
            record.page = entry.getKey();
            record.count = entry.getValue();
            record.recordedAt = now;
            try {
                onlineCountRepository.insert(record);
            } catch (Exception e) {
                System.err.println("[WARN] record failed: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    @GetMapping("/total-usage")
    public ResponseEntity<?> getTotalUsage() {
        long total = onlineCountRepository.sumAllCounts();
        return ResponseEntity.ok(Map.of("totalUsage", total));
    }
}
