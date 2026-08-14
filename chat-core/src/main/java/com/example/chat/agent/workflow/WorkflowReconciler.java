package com.example.chat.agent.workflow;

import com.example.chat.agent.planner.AgentWorkflowOrchestrator;
import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 工作流对账器（Reconciler）—— 收敛补偿兜底，方案 A（ZSet 索引版）。
 *
 * <p>纯事件驱动的收敛存在盲区：若子任务结果已全部到齐（received ≥ total）但收敛中途
 * 崩溃（JVM 退出 / 异常吞掉），重启后 result 队列已无新消息，收敛永远不会再触发，
 * 该 plan 会永久卡住（DB 永远不是 done）。本对账器周期性扫描 Redis 中的进行中 plan：</p>
 * <ul>
 *   <li>received ≥ total（结果已齐，与正常触发条件一致）；</li>
 *   <li>DB 中该请求状态 != done（收敛确实未完成）；</li>
 *   <li>收敛锁可获取（未被正常触发路径或其他实例占用）→ 重新触发
 *       {@link AgentWorkflowOrchestrator#converge(String)}。</li>
 * </ul>
 * <p><b>扫描索引</b>：任务启动时 planId 注册进 ZSet（{@code agent:reconciler:plans}），
 * score=下一次检查时间戳。结果未到齐时 score 保持“未来”不被返回；结果到齐后置 0，
 * 对账器仅需 {@code ZRANGEBYSCORE 0 now} 取出极少数到期的 plan——
 * 复杂度从全量 keys() 扫描的 O(N) 降到 O(logN)（跳表定位）+ 少量读取，
 * 百万级任务量下每 30s 对账不再打爆 Redis CPU。收敛成功后在 Orchestrator 侧移除成员。</p>
 * <p>双实例（9090/9092）同时运行安全：SETNX 收敛锁保证同一时刻只有一个实例真正触发；
 * 收敛结束后 DB 状态变 done，后续扫描自动跳过，不会重复执行。</p>
 */
@Component
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class WorkflowReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowReconciler.class);

    private static final String META_KEY_PREFIX = "agent:plan:";
    private static final String META_KEY_SUFFIX = ":meta";
    /** Reconciler 扫描索引（ZSet，与 AgentWorkflowOrchestrator.KEY_RECONCILER_ZSET 同键） */
    private static final String RECONCILER_ZSET = "agent:reconciler:plans";
    /** 单轮对账最多处理的候选数：超过则下轮继续（score=0 的候选不会被移除，不会丢失） */
    private static final int SCAN_LIMIT = 500;
    /** 对账触发的收敛锁 TTL：比正常路径（2min）更长，避免收敛超过 2 分钟时被重复触发 */
    private static final Duration RECONCILE_LOCK_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final MessageRepository messageRepository;
    private final AgentWorkflowOrchestrator orchestrator;
    private final ObjectMapper objectMapper;
    private final ExecutorService reconcileExecutor = ThreadPoolFactory.create(1, 2, 50, "agent-reconcile");

    @Autowired
    public WorkflowReconciler(StringRedisTemplate redisTemplate,
                              MessageRepository messageRepository,
                              AgentWorkflowOrchestrator orchestrator,
                              ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.messageRepository = messageRepository;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    /**
     * 周期性扫描（默认 30s）。ZSet 索引只返回 score≤now 的到期候选
     * （结果已到齐但未收敛完成的 plan），数量有界且与总量无关。
     */
    @Scheduled(fixedRateString = "${app.agent.planner.reconcile-interval-ms:30000}", initialDelay = 30000)
    public void reconcile() {
        try {
            Set<String> planIds = collectCandidates();
            if (planIds == null || planIds.isEmpty()) return;
            int triggered = 0;
            for (String planId : planIds) {
                if (tryReconcile(planId)) {
                    triggered++;
                }
            }
            if (triggered > 0) {
                log.info("[Reconciler] 本轮扫描 candidates={} 触发重新收敛 {} 个", planIds.size(), triggered);
            } else {
                log.debug("[Reconciler] 本轮扫描 candidates={} 无卡住的 plan", planIds.size());
            }
        } catch (Exception e) {
            log.warn("[Reconciler] 对账扫描异常: {}", e.getMessage());
        }
    }

    /**
     * 候选收集：优先走 ZSet 索引（O(logN) 定位 + 至多 SCAN_LIMIT 个候选）。
     * 仅当索引完全为空（如升级前已启动的存量 plan）时兜底全量 keys() 扫描，
     * 存量 plan 随 30min TTL 自然过期后不再触发兜底。
     */
    private Set<String> collectCandidates() {
        Long tracked = redisTemplate.opsForZSet().zCard(RECONCILER_ZSET);
        if (tracked != null && tracked > 0) {
            return redisTemplate.opsForZSet()
                    .rangeByScore(RECONCILER_ZSET, 0, System.currentTimeMillis(), 0, SCAN_LIMIT);
        }
        return legacyScan();
    }

    /** 兼容旧版本（未注册 ZSet）的兜底扫描：keys() 全量，仅 ZSet 完全为空时触发 */
    private Set<String> legacyScan() {
        Set<String> metaKeys = redisTemplate.keys(META_KEY_PREFIX + "*" + META_KEY_SUFFIX);
        if (metaKeys == null || metaKeys.isEmpty()) return Collections.emptySet();
        Set<String> planIds = new HashSet<>();
        for (String key : metaKeys) {
            String planId = extractPlanId(key);
            if (planId != null) {
                planIds.add(planId);
            }
        }
        return planIds;
    }

    @SuppressWarnings("PMD.NPathComplexity") // 对账逐级防御（结果齐/meta全/去重/锁抢占），拆分反而割裂状态机
    private boolean tryReconcile(String planId) {
        try {
            // 1. 结果必须已全部到齐（与 SubTaskResultCollector 正常触发条件一致）
            String totalStr = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyTotal(planId));
            if (totalStr == null) return false; // total 缺失（TTL 过期或未启动），无需对账
            long total = Long.parseLong(totalStr);
            if (total <= 0) return false;
            String receivedStr = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyReceived(planId));
            long received = receivedStr != null ? Long.parseLong(receivedStr) : 0;
            if (received < total) return false; // 子任务未完成：交给事件驱动 + RabbitMQ 重试恢复

            // 2. 元信息缺失（Redis 部分丢失）→ 无法收敛（拿不到 reqId/userId/question）
            String metaJson = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyMeta(planId));
            if (metaJson == null) {
                log.warn("[Reconciler] planId={} 结果已齐但 meta 缺失（Redis 数据不完整），跳过", planId);
                return false;
            }
            Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
            String reqId = meta.get("reqId") != null ? String.valueOf(meta.get("reqId")) : null;
            if (reqId == null || reqId.isBlank()) return false;

            // 3. 收敛完成标记存在 → 已收敛成功，跳过（主要去重依据：
            //    内部 API 请求可能没有 DB 行，DB 状态不可靠）
            String converged = redisTemplate.opsForValue().get(AgentWorkflowOrchestrator.keyConverged(planId));
            if (converged != null) {
                log.debug("[Reconciler] planId={} req_id={} 已收敛完成（converged 标记），跳过", planId, reqId);
                return false;
            }
            // 4. DB 已 done → 收敛已完成，跳过（DB 有行时的兜底去重）
            Message msg = messageRepository.findByReqId(reqId);
            if (msg != null && "done".equals(msg.status)) {
                log.debug("[Reconciler] planId={} req_id={} DB 已 done，跳过", planId, reqId);
                return false;
            }

            // 4. 抢占收敛锁（复用正常路径锁键；SETNX 保证双实例只触发一次）
            Boolean locked = redisTemplate.opsForValue()
                    .setIfAbsent(AgentWorkflowOrchestrator.keyLock(planId), "1", RECONCILE_LOCK_TTL);
            if (!Boolean.TRUE.equals(locked)) {
                log.debug("[Reconciler] planId={} 收敛锁被占用（收敛进行中），跳过", planId);
                return false;
            }

            log.warn("[Reconciler] planId={} req_id={} received={}/{} 结果已齐但未收敛完成，重新触发收敛",
                    planId, reqId, received, total);
            CompletableFuture.runAsync(() -> orchestrator.converge(planId), reconcileExecutor);
            return true;
        } catch (Exception e) {
            log.warn("[Reconciler] planId={} 对账异常: {}", planId, e.getMessage());
            return false;
        }
    }

    private String extractPlanId(String metaKey) {
        if (!metaKey.startsWith(META_KEY_PREFIX) || !metaKey.endsWith(META_KEY_SUFFIX)) return null;
        return metaKey.substring(META_KEY_PREFIX.length(), metaKey.length() - META_KEY_SUFFIX.length());
    }
}
