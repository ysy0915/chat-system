package com.example.chat.controller;

import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import com.example.chat.security.AdminAuthUtil;
import com.example.chat.service.OnlineCountRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final OnlineCountRepository onlineCountRepository;
    private final WebSocketSessionTracker sessionTracker;
    private final OnlineCountRedisService onlineCountRedisService;
    private final AdminAuthUtil adminAuthUtil;

    public MonitorController(OnlineCountRepository onlineCountRepository,
                             WebSocketSessionTracker sessionTracker,
                             OnlineCountRedisService onlineCountRedisService,
                             AdminAuthUtil adminAuthUtil) {
        this.onlineCountRepository = onlineCountRepository;
        this.sessionTracker = sessionTracker;
        this.onlineCountRedisService = onlineCountRedisService;
        this.adminAuthUtil = adminAuthUtil;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String pwd = body.get("password");
        if (pwd != null && adminAuthUtil.checkMonitorPassword(pwd)) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
    }

    @GetMapping("/online-history")
    public ResponseEntity<?> getOnlineHistory(@RequestParam(value = "days", required = false) Integer days,
                                              @RequestParam(value = "minutes", required = false) Integer minutes) {
        int safeDays = days == null ? 1 : Math.max(1, Math.min(days, 7));
        LocalDateTime since = minutes != null && minutes > 0
                ? LocalDateTime.now().minusMinutes(minutes)
                : LocalDateTime.now().minusDays(safeDays);
        Map<String, List<Map<String, Object>>> grouped = onlineCountRedisService.getHistorySince(since);
        if (grouped.isEmpty()) {
            List<OnlineCountRecord> records = onlineCountRepository.findRecent(since.toString().substring(0, 19).replace('T', ' '));
            grouped = new LinkedHashMap<>();
            for (OnlineCountRecord r : records) {
                grouped.computeIfAbsent(r.page, k -> new ArrayList<>())
                        .add(Map.of(
                                "time", r.recordedAt != null ? r.recordedAt.toString() : "",
                                "count", r.count
                        ));
            }
        }

        Map<String, Integer> currentCounts = sessionTracker.getAllCounts();
        Map<String, Integer> dailyVisits = onlineCountRedisService.getDailyVisitCounts(since);

        int hourlyTotal = onlineCountRedisService.getHourlyPeakTotal();

        return ResponseEntity.ok(Map.of(
                "history", grouped,
                "current", currentCounts,
                "dailyVisits", dailyVisits,
                "hourlyTotal", hourlyTotal
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
        onlineCountRedisService.recordSnapshot(allCounts, now);
        for (Map.Entry<String, Integer> entry : allCounts.entrySet()) {
            OnlineCountRecord record = new OnlineCountRecord();
            record.page = entry.getKey();
            record.count = entry.getValue();
            record.recordedAt = now;
            try {
                onlineCountRepository.insert(record);
            } catch (Exception e) {
                log.warn("[WARN] record failed: {}", e.getMessage());
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
