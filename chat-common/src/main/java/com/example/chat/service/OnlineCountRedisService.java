package com.example.chat.service;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class OnlineCountRedisService {

    private static final String PAGE_SET_KEY = "monitor:online:pages";
    private static final String HISTORY_PREFIX = "monitor:online:history:";
    private static final String VISIT_PREFIX = "monitor:visit:";
    private static final String HOURLY_ACTIVE_KEY = "monitor:active:1h";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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

    public void incrementVisitCount(String page, LocalDateTime recordedAt) {
        if (page == null || page.isBlank() || recordedAt == null) return;
        String pageKey = normalizePage(page);
        String dateStr = recordedAt.format(DATE_FORMATTER);
        String visitKey = VISIT_PREFIX + pageKey + ":" + dateStr;
        redisTemplate.opsForValue().increment(visitKey, 1);
        redisTemplate.expire(visitKey, 10, TimeUnit.DAYS);

        // 记录 1 小时内活跃页面访问（用 Sorted Set，score 为时间戳，1 小时前自动清理）
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().add(HOURLY_ACTIVE_KEY, pageKey + ":" + now, now);
        redisTemplate.opsForZSet().removeRangeByScore(HOURLY_ACTIVE_KEY, 0, now - 3600000);
        redisTemplate.expire(HOURLY_ACTIVE_KEY, 2, TimeUnit.HOURS);
    }

    public Map<String, Integer> getDailyVisitCounts(LocalDateTime since) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (since == null) return result;

        Set<String> pages = redisTemplate.opsForSet().members(PAGE_SET_KEY);
        if (pages == null || pages.isEmpty()) return result;

        LocalDateTime now = LocalDateTime.now();
        LocalDate startDate = since.toLocalDate();
        LocalDate endDate = now.toLocalDate();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            String dateStr = date.format(DATE_FORMATTER);
            // 批量获取所有页面当天的访问量（1次Redis mget替代N次get）
            List<String> visitKeys = new ArrayList<>();
            for (String rawPage : pages) {
                String page = normalizePage(rawPage);
                visitKeys.add(VISIT_PREFIX + page + ":" + dateStr);
            }
            List<String> values = redisTemplate.opsForValue().multiGet(visitKeys);
            int dayTotal = 0;
            if (values != null) {
                for (String val : values) {
                    if (val != null) {
                        try {
                            dayTotal += Integer.parseInt(val);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            result.put(dateStr, dayTotal);
        }
        return result;
    }

    /** 获取各页面按日期的访问量 {page: {dateStr: count}} */
    public Map<String, Map<String, Integer>> getPageDailyVisitCounts(LocalDateTime since) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        if (since == null) return result;

        Set<String> pages = redisTemplate.opsForSet().members(PAGE_SET_KEY);
        if (pages == null || pages.isEmpty()) return result;

        LocalDate startDate = since.toLocalDate();
        LocalDate endDate = LocalDate.now();

        for (String rawPage : pages) {
            String page = normalizePage(rawPage);
            Map<String, Integer> pageData = new LinkedHashMap<>();
            // 批量获取该页面所有日期的访问量
            List<String> visitKeys = new ArrayList<>();
            List<String> dateStrs = new ArrayList<>();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                String dateStr = date.format(DATE_FORMATTER);
                visitKeys.add(VISIT_PREFIX + page + ":" + dateStr);
                dateStrs.add(dateStr);
            }
            List<String> values = redisTemplate.opsForValue().multiGet(visitKeys);
            for (int i = 0; i < dateStrs.size(); i++) {
                String val = values != null && i < values.size() ? values.get(i) : null;
                pageData.put(dateStrs.get(i), val != null ? Integer.parseInt(val) : 0);
            }
            result.put(page, pageData);
        }
        return result;
    }

    public int getHourlyPeakTotal() {
        // 今日累计在线人数 = 今天各页面访问次数的总和
        java.time.LocalDate today = java.time.LocalDate.now();
        String todayStr = today.format(DATE_FORMATTER);
        Set<String> pages = redisTemplate.opsForSet().members(PAGE_SET_KEY);
        if (pages == null || pages.isEmpty()) return 0;

        int total = 0;
        for (String rawPage : pages) {
            String page = normalizePage(rawPage);
            String visitKey = VISIT_PREFIX + page + ":" + todayStr;
            String val = redisTemplate.opsForValue().get(visitKey);
            if (val != null) {
                try {
                    total += Integer.parseInt(val);
                } catch (NumberFormatException ignored) {}
            }
        }
        return total;
    }

    /** 获取最近 1 小时内活跃访问次数（独立访问事件数） */
    public int getHourlyActiveCount() {
        long now = System.currentTimeMillis();
        // 清理过期数据
        redisTemplate.opsForZSet().removeRangeByScore(HOURLY_ACTIVE_KEY, 0, now - 3600000);
        Long count = redisTemplate.opsForZSet().zCard(HOURLY_ACTIVE_KEY);
        return count != null ? count.intValue() : 0;
    }

    private String normalizePage(String page) {
        return page == null || page.isBlank() ? "global" : page.trim();
    }
}
