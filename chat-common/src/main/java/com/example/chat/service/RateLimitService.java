package com.example.chat.service;

import com.example.chat.security.RateLimitChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private final RateLimitChecker rateLimitChecker;

    private static final int PER_MINUTE_LIMIT = 20;
    private static final int PER_HOUR_LIMIT = 200;

    public RateLimitService(RateLimitChecker rateLimitChecker) {
        this.rateLimitChecker = rateLimitChecker;
    }

    public boolean isAllowed(Long userId) {
        if (!rateLimitChecker.checkAndIncrement("rate:user:" + userId + ":min", PER_MINUTE_LIMIT, Duration.ofMinutes(1))) {
            log.warn("[RATE_LIMIT] userId={} minute limit exceeded, BLOCKED", userId);
            return false;
        }
        if (!rateLimitChecker.checkAndIncrement("rate:user:" + userId + ":hour", PER_HOUR_LIMIT, Duration.ofHours(1))) {
            log.warn("[RATE_LIMIT] userId={} hour limit exceeded, BLOCKED", userId);
            return false;
        }
        return true;
    }

    public long getRemainingSeconds(Long userId) {
        return rateLimitChecker.getRemainingSeconds("rate:user:" + userId + ":min", 60);
    }
}
