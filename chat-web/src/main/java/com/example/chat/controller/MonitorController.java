package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import com.example.chat.security.AdminAuthUtil;
import com.example.chat.service.OnlineCountRedisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final CoreClient coreClient;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    public MonitorController(OnlineCountRepository onlineCountRepository,
                             WebSocketSessionTracker sessionTracker,
                             OnlineCountRedisService onlineCountRedisService,
                             AdminAuthUtil adminAuthUtil,
                             CoreClient coreClient) {
        this.onlineCountRepository = onlineCountRepository;
        this.sessionTracker = sessionTracker;
        this.onlineCountRedisService = onlineCountRedisService;
        this.adminAuthUtil = adminAuthUtil;
        this.coreClient = coreClient;
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

        Map<String, Integer> currentCounts = sessionTracker.getAllRealCounts();
        Map<String, Integer> dailyVisits = onlineCountRedisService.getDailyVisitCounts(since);
        Map<String, Map<String, Integer>> pageDailyVisits = onlineCountRedisService.getPageDailyVisitCounts(since);
        int hourlyTotal = onlineCountRedisService.getHourlyPeakTotal();
        int hourlyActive = onlineCountRedisService.getHourlyActiveCount();

        return ResponseEntity.ok(Map.of(
                "current", currentCounts,
                "dailyVisits", dailyVisits,
                "pageDailyVisits", pageDailyVisits,
                "hourlyTotal", hourlyTotal,
                "hourlyActive", hourlyActive
        ));
    }

    @GetMapping("/current")
    public ResponseEntity<?> getCurrentCounts() {
        return ResponseEntity.ok(sessionTracker.getAllRealCounts());
    }

    @PostMapping("/record")
    public ResponseEntity<?> recordCurrentCounts() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> allCounts = sessionTracker.getAllRealCounts();
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

    @GetMapping("/llm-stats")
    public ResponseEntity<?> getLlmStats(@RequestParam(value = "date", required = false) String date) {
        if (date == null || date.isBlank()) {
            date = java.time.LocalDate.now().toString();
        }
        String key = "llm:stats:" + date;
        Map<Object, Object> raw = redisTemplate.opsForHash().entries(key);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String provider = entry.getKey().toString();
            String json = entry.getValue().toString();
            Map<String, Object> stats = new java.util.HashMap<>();
            json = json.replaceAll("[{}\"]", "");
            for (String part : json.split(",")) {
                String[] kv = part.split(":");
                if (kv.length == 2) {
                    try {
                        stats.put(kv[0].trim(), Long.parseLong(kv[1].trim()));
                    } catch (NumberFormatException e) {
                        stats.put(kv[0].trim(), kv[1].trim());
                    }
                }
            }
            long total = (long) stats.getOrDefault("total", 0L);
            long totalLatency = (long) stats.getOrDefault("totalLatency", 0L);
            if (total > 0) {
                stats.put("avgLatency", totalLatency / total);
            }
            result.put(provider, stats);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/total-usage")
    public ResponseEntity<?> getTotalUsage() {
        long total = onlineCountRepository.sumAllCounts();
        return ResponseEntity.ok(Map.of("totalUsage", total));
    }

    /**
     * 获取最近调用链路（通过 CoreClient 调用 chat-core）
     */
    @GetMapping("/traces")
    public ResponseEntity<?> getRecentTraces(@RequestParam(value = "n", required = false) Integer n) {
        int count = n == null ? 20 : Math.max(1, Math.min(n, 1000));
        try {
            return ResponseEntity.ok(coreClient.getRecentTraces(count));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
    }

    /**
     * 获取错误聚合统计（通过 CoreClient 调用 chat-core）
     */
    @GetMapping("/errors")
    public ResponseEntity<?> getErrorStats() {
        try {
            return ResponseEntity.ok(coreClient.getErrorStats());
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("enabled", false, "errors", Collections.emptyList()));
        }
    }

    /**
     * 搜索调用链路（通过 CoreClient 调用 chat-core）
     */
    @GetMapping("/traces/search")
    public ResponseEntity<?> searchTraces(@RequestParam(value = "keyword", required = false) String keyword) {
        try {
            return ResponseEntity.ok(coreClient.searchTraces(keyword));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
    }
}
