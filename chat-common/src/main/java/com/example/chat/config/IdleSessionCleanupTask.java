package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理超过 5 分钟无活动的 WebSocket session
 * 作为前端主动断开的兜底机制（防止前端崩溃、网络异常等导致僵尸 session）
 */
@Component
@ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class IdleSessionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(IdleSessionCleanupTask.class);

    private final WebSocketSessionTracker sessionTracker;

    public IdleSessionCleanupTask(WebSocketSessionTracker sessionTracker) {
        this.sessionTracker = sessionTracker;
    }

    /** 每 60 秒扫描一次，清理超时 session */
    @Scheduled(fixedDelay = 60000)
    public void cleanup() {
        try {
            sessionTracker.cleanupIdleSessions();
        } catch (Exception e) {
            log.warn("清理超时 session 失败: {}", e.getMessage());
        }
    }
}
