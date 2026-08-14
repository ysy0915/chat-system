package com.example.chat.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 错误聚合器
 * 用 Redis Hash 聚合：key=error:agg:{date}, field={provider}:{errorType}, value=count
 */
@Service
@ConditionalOnProperty(name = "app.observability.enabled", havingValue = "true", matchIfMissing = true)
public class ErrorAggregator {

    private static final Logger log = LoggerFactory.getLogger(ErrorAggregator.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private String todayKey() {
        return "error:agg:" + LocalDate.now().format(DATE_FMT);
    }

    /**
     * 记录一次错误
     */
    public void recordError(String scene, String provider, String model, ErrorType errorType, String errorMessage) {
        try {
            String key = todayKey();
            String field = provider + ":" + errorType.name();
            Long count = stringRedisTemplate.opsForHash().increment(key, field, 1);
            // 记录最近一次错误信息到单独 field
            stringRedisTemplate.opsForHash().put(key, field + ":lastMsg",
                    errorMessage != null ? errorMessage : "");
            stringRedisTemplate.opsForHash().put(key, field + ":lastModel",
                    model != null ? model : "");
            stringRedisTemplate.opsForHash().put(key, field + ":lastScene",
                    scene != null ? scene : "");
            stringRedisTemplate.expire(key, Duration.ofDays(30));
            log.debug("[ErrorAggregator] provider={} errorType={} count={}", provider, errorType, count);
        } catch (Exception e) {
            log.warn("[ErrorAggregator] 记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取今日错误聚合统计
     * 返回结构：[{provider, errorType, count, lastMessage, lastModel, lastScene}, ...]
     */
    public List<Map<String, Object>> getErrorStats() {
        try {
            Map<Object, Object> raw = stringRedisTemplate.opsForHash().entries(todayKey());
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> entry : raw.entrySet()) {
                String field = entry.getKey().toString();
                Object value = entry.getValue();
                if (field.endsWith(":lastMsg") || field.endsWith(":lastModel") || field.endsWith(":lastScene")) {
                    // 附属信息
                    String base = field.substring(0, field.lastIndexOf(':'));
                    Map<String, Object> m = grouped.computeIfAbsent(base, k -> {
                        Map<String, Object> nm = new LinkedHashMap<>();
                        int idx = k.indexOf(':');
                        if (idx > 0) {
                            nm.put("provider", k.substring(0, idx));
                            nm.put("errorType", k.substring(idx + 1));
                        }
                        nm.put("count", 0L);
                        return nm;
                    });
                    String suffix = field.substring(field.lastIndexOf(':') + 1);
                    if ("lastMsg".equals(suffix)) m.put("lastMessage", value);
                    else if ("lastModel".equals(suffix)) m.put("lastModel", value);
                    else if ("lastScene".equals(suffix)) m.put("lastScene", value);
                } else {
                    // 计数字段
                    Map<String, Object> m = grouped.computeIfAbsent(field, k -> {
                        Map<String, Object> nm = new LinkedHashMap<>();
                        int idx = k.indexOf(':');
                        if (idx > 0) {
                            nm.put("provider", k.substring(0, idx));
                            nm.put("errorType", k.substring(idx + 1));
                        }
                        nm.put("count", 0L);
                        return nm;
                    });
                    try {
                        m.put("count", Long.parseLong(value.toString()));
                    } catch (NumberFormatException e) {
                        m.put("count", value);
                    }
                }
            }
            return new ArrayList<>(grouped.values());
        } catch (Exception e) {
            log.warn("[ErrorAggregator] 读取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 返回出现次数最多的前 N 个错误
     */
    public List<Map<String, Object>> getTopErrors(int n) {
        List<Map<String, Object>> all = getErrorStats();
        all.sort((a, b) -> {
            long ca = toLong(a.get("count"));
            long cb = toLong(b.get("count"));
            return Long.compare(cb, ca);
        });
        int limit = n;
        if (limit <= 0) limit = 10;
        if (limit > all.size()) limit = all.size();
        return new ArrayList<>(all.subList(0, limit));
    }

    private static long toLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
