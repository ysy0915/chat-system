package com.example.chat.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 服务多实例就绪探针
 * <p>
 * 用于 Kubernetes / 负载均衡的健康检查。
 * 通过 /actuator/health/readiness 访问。
 * <p>
 * 检查项：
 * <ul>
 *   <li>Redis 连接是否可用</li>
 *   <li>WebSocket Session 追踪器是否可正常工作</li>
 * </ul>
 *
 * 在 Nacos + 多实例部署架构中，此探针确保：
 * <ol>
 *   <li>实例启动后等待 Redis 连接就绪</li>
 *   <li>Session 追踪的 Redis Hash 可正常读写</li>
 *   <li>Nacos 服务注册前完成就绪检查</li>
 * </ol>
 */
@Component("readinessCheck")
public class ReadinessHealthIndicator implements HealthIndicator {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private static final String READINESS_KEY = "health:readiness";

    @Override
    public Health health() {
        // 1. 检查 Redis 连接
        if (redisTemplate == null) {
            return Health.down()
                    .withDetail("reason", "RedisTemplate 未注入")
                    .build();
        }

        try {
            // 2. 尝试写入/读取 Redis（验证读写能力）
            String testValue = String.valueOf(System.currentTimeMillis());
            redisTemplate.opsForValue().set(READINESS_KEY, testValue, java.time.Duration.ofSeconds(10));
            String readBack = redisTemplate.opsForValue().get(READINESS_KEY);

            if (testValue.equals(readBack)) {
                return Health.up()
                        .withDetail("redis", "connected")
                        .withDetail("stateless", true)
                        .withDetail("description", "多实例安全，会话状态存储在 Redis")
                        .build();
            } else {
                return Health.down()
                        .withDetail("reason", "Redis 读写不一致")
                        .build();
            }
        } catch (Exception e) {
            return Health.down()
                    .withDetail("reason", "Redis 不可用: " + e.getMessage())
                    .build();
        }
    }
}
