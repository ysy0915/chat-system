package com.example.chat.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路记录器
 * 存入 Redis List（key: trace:{date}），保留最近 N 条
 */
@Service
@ConditionalOnProperty(name = "app.observability.enabled", havingValue = "true", matchIfMissing = true)
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Value("${app.observability.trace-retention:1000}")
    private int retention;

    private String todayKey() {
        return "trace:" + LocalDate.now().format(DATE_FMT);
    }

    /**
     * 记录一条调用链路
     */
    public void record(CallTrace trace) {
        try {
            String key = todayKey();
            stringRedisTemplate.opsForList().leftPush(key, trace.toJson());
            // 保留最近 retention 条
            stringRedisTemplate.opsForList().trim(key, 0, retention - 1);
            // 30 天过期
            stringRedisTemplate.expire(key, Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("[TraceRecorder] 记录失败: {}", e.getMessage());
        }
    }

    /**
     * 获取最近 N 条链路
     */
    public List<Map<String, Object>> getRecentTraces(int n) {
        try {
            if (n <= 0) n = 20;
            if (n > retention) n = retention;
            List<String> raw = stringRedisTemplate.opsForList().range(todayKey(), 0, n - 1);
            if (raw == null) return Collections.emptyList();
            List<Map<String, Object>> result = new ArrayList<>(raw.size());
            for (String json : raw) {
                Map<String, Object> m = parseSimpleJson(json);
                if (!m.isEmpty()) result.add(m);
            }
            return result;
        } catch (Exception e) {
            log.warn("[TraceRecorder] 读取失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按场景/状态搜索链路
     */
    public List<Map<String, Object>> searchTraces(String keyword) {
        try {
            if (keyword == null || keyword.isBlank()) {
                return getRecentTraces(50);
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            List<String> raw = stringRedisTemplate.opsForList().range(todayKey(), 0, -1);
            if (raw == null) return Collections.emptyList();
            List<Map<String, Object>> result = new ArrayList<>();
            for (String json : raw) {
                if (json.toLowerCase(Locale.ROOT).contains(kw)) {
                    Map<String, Object> m = parseSimpleJson(json);
                    if (!m.isEmpty()) result.add(m);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[TraceRecorder] 搜索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 简易 JSON 解析（不引入 Jackson）
     */
    private static Map<String, Object> parseSimpleJson(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        Map<String, Object> map = new LinkedHashMap<>();
        String body = json.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        // 按逗号切分（简化处理，不考虑嵌套）
        int i = 0;
        while (i < body.length()) {
            // 找 key
            int kStart = body.indexOf('"', i);
            if (kStart < 0) break;
            int kEnd = body.indexOf('"', kStart + 1);
            if (kEnd < 0) break;
            String key = body.substring(kStart + 1, kEnd);
            int colon = body.indexOf(':', kEnd + 1);
            if (colon < 0) break;
            // 解析 value
            int v = colon + 1;
            while (v < body.length() && Character.isWhitespace(body.charAt(v))) v++;
            if (v >= body.length()) break;
            Object val;
            if (body.charAt(v) == '"') {
                int vEnd = body.indexOf('"', v + 1);
                if (vEnd < 0) break;
                val = body.substring(v + 1, vEnd);
                i = vEnd + 1;
            } else {
                int comma = body.indexOf(',', v);
                String numStr = (comma < 0 ? body.substring(v) : body.substring(v, comma)).trim();
                try {
                    val = Long.parseLong(numStr);
                } catch (NumberFormatException e) {
                    val = numStr;
                }
                i = comma < 0 ? body.length() : comma;
            }
            map.put(key, val);
            // 跳到下一个逗号之后
            int nextComma = body.indexOf(',', i);
            if (nextComma < 0) break;
            i = nextComma + 1;
        }
        return map;
    }
}
