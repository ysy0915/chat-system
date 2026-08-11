package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.config.WebSocketSessionTracker;
import com.example.chat.entity.OnlineCountRecord;
import com.example.chat.repository.OnlineCountRepository;
import com.example.chat.security.AdminAuthUtil;
import com.example.chat.service.OnlineCountRedisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

@Tag(name = "监控面板", description = "在线人数、LLM 调用统计、调用链路追踪（需密码认证）")
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

    @Operation(summary = "监控登录", description = "输入管理密码获取监控面板访问权限")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String pwd = body.get("password");
        if (pwd != null && adminAuthUtil.checkMonitorPassword(pwd)) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        return ResponseEntity.status(401).body(Map.of("error", "密码错误"));
    }

    @Operation(summary = "在线历史", description = "获取在线人数历史曲线（按小时/天，支持 1-7 天）")
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

    @Operation(summary = "当前在线", description = "获取各页面当前实时在线人数")
    @GetMapping("/current")
    public ResponseEntity<?> getCurrentCounts() {
        return ResponseEntity.ok(sessionTracker.getAllRealCounts());
    }

    @Operation(summary = "记录快照", description = "手动触发当前在线人数快照记录到数据库")
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
            } catch (DataAccessException e) {
                log.warn("[WARN] record failed: {}", e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("recorded", true));
    }

    @Operation(summary = "LLM 统计", description = "按天获取各模型的调用次数和平均延迟统计")
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

    @Operation(summary = "累计使用量", description = "获取系统累计在线人次总和")
    @GetMapping("/total-usage")
    public ResponseEntity<?> getTotalUsage() {
        long total = onlineCountRepository.sumAllCounts();
        return ResponseEntity.ok(Map.of("totalUsage", total));
    }

    /**
     * 获取最近调用链路（通过 CoreClient 调用 chat-core）
     */
    @Operation(summary = "调用链路", description = "获取最近的 LLM 调用链路追踪记录")
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
    @Operation(summary = "错误统计", description = "获取 LLM 调用错误聚合统计")
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
    @Operation(summary = "搜索链路", description = "按关键词搜索 LLM 调用链路")
    @GetMapping("/traces/search")
    public ResponseEntity<?> searchTraces(@RequestParam(value = "keyword", required = false) String keyword) {
        try {
            return ResponseEntity.ok(coreClient.searchTraces(keyword));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
    }
}
