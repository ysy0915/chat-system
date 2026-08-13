package com.example.chat.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 注册验证码服务：生成一次性算术验证码，答案存 Redis（5 分钟有效，用完即焚）。
 * 防止注册接口被自动化脚本批量刷号 / 用户名枚举。
 */
@Service
public class CaptchaService {
    private static final String KEY_PREFIX = "captcha:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public CaptchaService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 生成验证码：返回 {captcha_token, question}，答案存 Redis */
    public Map<String, String> generate() {
        int a = ThreadLocalRandom.current().nextInt(1, 20);
        int b = ThreadLocalRandom.current().nextInt(1, 20);
        int answer = a + b;
        String token = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(KEY_PREFIX + token, String.valueOf(answer), TTL);
        return Map.of("captcha_token", token, "question", a + " + " + b + " = ?");
    }

    /** 校验并消费验证码（一次性使用，验证后立即失效） */
    public boolean verify(String token, String answer) {
        if (token == null || token.isBlank() || answer == null) return false;
        String key = KEY_PREFIX + token.trim();
        String stored = redis.opsForValue().get(key);
        if (stored == null) return false;
        redis.delete(key);
        return stored.equals(answer.trim());
    }
}
