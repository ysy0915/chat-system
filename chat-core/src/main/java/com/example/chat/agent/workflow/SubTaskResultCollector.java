package com.example.chat.agent.workflow;

import com.example.chat.agent.planner.AgentWorkflowOrchestrator;
import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.dto.WsMessage;
import com.example.chat.service.BroadcastService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 子任务结果收集器 —— Step 3 结果收敛触发。
 *
 * <p>从 {@code agent.subtask.result.queue} 消费 {@link SubAgentResult}：</p>
 * <ol>
 *   <li>结果写入 Redis hash（按 planId 聚合，覆盖幂等）；</li>
 *   <li>INCR 已收计数并推送前端进度（plan_progress）；</li>
 *   <li>全部到齐后，经 Redis SETNX 分布式锁（双实例只收敛一次）异步触发主 Agent 收敛。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class SubTaskResultCollector {

    private static final Logger log = LoggerFactory.getLogger(SubTaskResultCollector.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BroadcastService broadcastService;
    private final AgentWorkflowOrchestrator orchestrator;

    private final ExecutorService collectExecutor = ThreadPoolFactory.create(2, 4, 50, "agent-collect");

    /** Reconciler 对账扫描周期（毫秒）：结果未到齐时 ZSet 分数推到未来，避免被扫描 */
    @Value("${app.agent.planner.reconcile-interval-ms:30000}")
    private long reconcileIntervalMs;

    @Autowired
    public SubTaskResultCollector(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  BroadcastService broadcastService,
                                  AgentWorkflowOrchestrator orchestrator) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.orchestrator = orchestrator;
    }

    @RabbitListener(queues = SubTaskRabbitConfig.SUBTASK_RESULT_QUEUE, ackMode = "MANUAL")
    public void onResult(SubAgentResult result, Channel channel,
                         @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        if (result == null || result.planId == null || result.taskId == null) {
            basicAck(channel, deliveryTag);
            return;
        }
        String planId = result.planId;
        try {
            // 1. 结果写入 hash（幂等覆盖），刷新 TTL
            redisTemplate.opsForHash().put(AgentWorkflowOrchestrator.keyResultHash(planId),
                    result.taskId, objectMapper.writeValueAsString(result));
            redisTemplate.expire(AgentWorkflowOrchestrator.keyResultHash(planId), Duration.ofMinutes(30));

            // 2. 计数 + 进度推送
            Long received = redisTemplate.opsForValue().increment(AgentWorkflowOrchestrator.keyReceived(planId));
            redisTemplate.expire(AgentWorkflowOrchestrator.keyReceived(planId), Duration.ofMinutes(30));
            String totalStr = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyTotal(planId));
            long total = totalStr != null ? Long.parseLong(totalStr) : 0;
            boolean complete = total > 0 && received != null && received >= total;
            pushProgress(planId, result, received, total);

            // 2.5 维护 Reconciler 扫描索引：未到齐保持“未来”分数（不被扫描），
            //     到齐置 0（下轮对账扫描立即纳入，保证崩溃后能重新收敛）
            updateReconcilerScore(planId, complete);

            // 3. 全部到齐 → 分布式锁 → 异步收敛（保证双实例只收敛一次）
            if (complete) {
                Boolean locked = redisTemplate.opsForValue()
                        .setIfAbsent(AgentWorkflowOrchestrator.keyLock(planId), "1",
                                AgentWorkflowOrchestrator.CONVERGE_LOCK_TTL);
                if (Boolean.TRUE.equals(locked)) {
                    log.info("[SubTaskCollector] planId={} 全部 {} 个结果已到齐，触发收敛", planId, total);
                    CompletableFuture.runAsync(() -> orchestrator.converge(planId), collectExecutor);
                } else {
                    log.debug("[SubTaskCollector] planId={} 收敛已由其他实例触发，跳过", planId);
                }
            } else {
                log.debug("[SubTaskCollector] planId={} 进度 {}/{}", planId, received, total);
            }
            basicAck(channel, deliveryTag);
        } catch (Exception e) {
            log.error("[SubTaskCollector] 结果处理失败 planId={} taskId={}: {}",
                    planId, result.taskId, e.getMessage());
            // requeue=true：结果丢失会导致收敛永远等不到，必须重试（Redis 操作幂等，重试安全）
            basicNack(channel, deliveryTag, true);
        }
    }

    private void basicAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("[SubTaskCollector] basicAck 失败 tag={}: {}", deliveryTag, e.getMessage());
        }
    }

    private void basicNack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.warn("[SubTaskCollector] basicNack 失败 tag={}: {}", deliveryTag, e.getMessage());
        }
    }

    /** 维护 Reconciler ZSet 索引分数：结果到齐置 0（立即进入对账扫描），未到齐推到未来（跳过扫描） */
    private void updateReconcilerScore(String planId, boolean complete) {
        try {
            double score = complete ? 0 : System.currentTimeMillis() + reconcileIntervalMs;
            redisTemplate.opsForZSet().add(AgentWorkflowOrchestrator.keyReconcilerZSet(), planId, score);
            redisTemplate.expire(AgentWorkflowOrchestrator.keyReconcilerZSet(), Duration.ofMinutes(30));
        } catch (Exception e) {
            log.warn("[SubTaskCollector] planId={} 更新 Reconciler 索引异常: {}", planId, e.getMessage());
        }
    }

    /** 推送子任务完成进度到前端 */
    private void pushProgress(String planId, SubAgentResult result, Long received, long total) {
        try {
            String metaJson = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyMeta(planId));
            if (metaJson == null) return;
            Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
            String reqId = (String) meta.get("reqId");
            Object userId = meta.get("userId");
            if (userId == null) return;
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.of(WsMessage.TYPE_PLAN_PROGRESS).withReqId(reqId)
                            .with("planId", planId)
                            .with("taskId", result.taskId)
                            .with("success", result.success)
                            .with("done", received != null ? received : 0)
                            .with("total", total)
                            .toMap());
        } catch (JsonProcessingException e) {
            log.debug("[SubTaskCollector] meta 解析失败: {}", e.getMessage());
        }
    }
}
