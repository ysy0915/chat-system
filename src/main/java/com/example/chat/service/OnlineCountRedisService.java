package com.example.chat.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class OnlineCountRedisService {

    private static final String PAGE_SET_KEY = "monitor:online:pages";
    private static final String HISTORY_PREFIX = "monitor:online:history:";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final RedisTemplate<String, String> redisTemplate;

    public OnlineCountRedisService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void recordSnapshot(Map<String, Integer> counts, LocalDateTime recordedAt) {
        if (counts == null || counts.isEmpty() || recordedAt == null) {
            return;
        }

        long epochMillis = recordedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String page = normalizePage(entry.getKey());
            int count = Math.max(0, entry.getValue() == null ? 0 : entry.getValue());
            String member = epochMillis + ":" + count;
            String historyKey = HISTORY_PREFIX + page;

            redisTemplate.opsForSet().add(PAGE_SET_KEY, page);
            redisTemplate.opsForZSet().add(historyKey, member, epochMillis);
            redisTemplate.expire(historyKey, 10, TimeUnit.DAYS);
        }
        redisTemplate.expire(PAGE_SET_KEY, 30, TimeUnit.DAYS);
    }

    public Map<String, List<Map<String, Object>>> getHistorySince(LocalDateTime since) {
        Map<String, List<Map<String, Object>>> history = new LinkedHashMap<>();
        if (since == null) {
            return history;
        }

        long minScore = since.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long maxScore = System.currentTimeMillis();
        Set<String> pages = redisTemplate.opsForSet().members(PAGE_SET_KEY);
        if (pages == null || pages.isEmpty()) {
            return history;
        }

        for (String rawPage : new LinkedHashSet<>(pages)) {
            String page = normalizePage(rawPage);
            Set<String> members = redisTemplate.opsForZSet().rangeByScore(HISTORY_PREFIX + page, minScore, maxScore);
            if (members == null || members.isEmpty()) {
                history.put(page, new ArrayList<>());
                continue;
            }

            List<Map<String, Object>> points = new ArrayList<>();
            for (String member : members) {
                int separator = member.indexOf(':');
                if (separator <= 0 || separator >= member.length() - 1) {
                    continue;
                }
                try {
                    long epochMillis = Long.parseLong(member.substring(0, separator));
                    int count = Integer.parseInt(member.substring(separator + 1));
                    String time = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                            .format(DATE_TIME_FORMATTER);
                    points.add(Map.of("time", time, "count", count));
                } catch (NumberFormatException ignored) {
                }
            }
            history.put(page, points);
        }

        return history;
    }

    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }
}
