package com.example.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 模型调用统计记录器
 * 使用 Redis Hash 存储每日调用统计，按 provider 聚合
 * Key: llm:stats:{date}  Field: {provider}  Value: JSON {total, success, fail, totalLatency, totalTokens}
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class LLMCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(LLMCallRecorder.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 记录一次模型调用
     * @param provider 模型提供商 (deepseek/qwen/doubao)
     * @param model 模型名称
     * @param scene 调用场景 (chat/debate/treehole/auto/personal/media)
     * @param success 是否成功
     * @param latencyMs 耗时（毫秒）
     * @param answerLen 回答长度
     */
    public void record(String provider, String model, String scene, boolean success, long latencyMs, int answerLen) {
        try {
            String date = LocalDate.now().format(DATE_FMT);
            String key = "llm:stats:" + date;
            String field = provider;

            // 读取现有统计
            String existing = redisTemplate.opsForHash().get(key, field) != null
                    ? redisTemplate.opsForHash().get(key, field).toString() : null;

            Map<String, Object> stats;
            if (existing != null && !existing.isBlank()) {
                stats = new HashMap<>();
                // 简单解析 JSON（避免引入 Jackson）
                String[] parts = existing.replaceAll("[{}\"]", "").split(",");
                for (String part : parts) {
                    String[] kv = part.split(":");
                    if (kv.length == 2) {
                        try {
                            stats.put(kv[0].trim(), Long.parseLong(kv[1].trim()));
                        } catch (NumberFormatException e) {
                            stats.put(kv[0].trim(), kv[1].trim());
                        }
                    }
                }
            } else {
                stats = new HashMap<>();
                stats.put("total", 0L);
                stats.put("success", 0L);
                stats.put("fail", 0L);
                stats.put("totalLatency", 0L);
                stats.put("totalAnswerLen", 0L);
            }

            stats.put("total", (Long) stats.get("total") + 1);
            if (success) {
                stats.put("success", (Long) stats.get("success") + 1);
            } else {
                stats.put("fail", (Long) stats.get("fail") + 1);
            }
            stats.put("totalLatency", (Long) stats.get("totalLatency") + latencyMs);
            stats.put("totalAnswerLen", (Long) stats.get("totalAnswerLen") + answerLen);

            // 构建简单 JSON
            StringBuilder json = new StringBuilder("{");
            json.append("\"total\":").append(stats.get("total")).append(",");
            json.append("\"success\":").append(stats.get("success")).append(",");
            json.append("\"fail\":").append(stats.get("fail")).append(",");
            json.append("\"totalLatency\":").append(stats.get("totalLatency")).append(",");
            json.append("\"totalAnswerLen\":").append(stats.get("totalAnswerLen"));
            json.append("}");

            redisTemplate.opsForHash().put(key, field, json.toString());
            // 30 天过期
            redisTemplate.expire(key, java.time.Duration.ofDays(30));
        } catch (Exception e) {
            log.warn("[LLMStats] 记录失败: {}", e.getMessage());
        }
    }
}
