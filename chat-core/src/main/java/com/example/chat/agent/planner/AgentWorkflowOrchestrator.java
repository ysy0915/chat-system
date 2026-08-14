package com.example.chat.agent.planner;

import com.example.chat.agent.protocol.SubAgentPlan;
import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.workflow.SubTaskProducer;
import com.example.chat.agent.workflow.SubTaskResultCollector;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.ChatServiceException;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ChatProcessor;
import com.example.chat.service.LLMInvoker;
import com.example.chat.service.ModelRouter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Multi-Agent 并行工作流指挥官（Orchestrator）—— Step 2 + Step 3 编排核心。
 *
 * <p>流程：</p>
 * <ol>
 *   <li>主 Agent 收到超长/跨域请求 → {@link TaskPlanner} 生成拆解计划；</li>
 *   <li>计划子任务经 RabbitMQ 分发到 Worker 并行执行（极短独立上下文）；</li>
 *   <li>Worker 回传结构化摘要，由 {@link SubTaskResultCollector} 聚合计数；</li>
 *   <li>全部完成后本类 {@link #converge(String)} 主 Agent 流式总结输出最终回答。</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class AgentWorkflowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowOrchestrator.class);

    private static final String KEY_PLAN = "agent:plan:%s";
    private static final String KEY_META = "agent:plan:%s:meta";
    private static final String KEY_TOTAL = "agent:plan:%s:total";
    private static final String KEY_RECEIVED = "agent:plan:%s:received";
    private static final String KEY_RESULT_HASH = "agent:subtask:result:%s";
    private static final String KEY_LOCK = "agent:plan:%s:lock";
    /** 收敛成功标记（30min 与工作流状态同生命周期）：供 Reconciler 去重，防止重复触发收敛 */
    private static final String KEY_CONVERGED = "agent:plan:%s:converged";

    /**
     * Reconciler 扫描索引（ZSet）：成员为进行中的 planId，score=下次对账检查时间戳。
     * 结果未到齐时 score 保持“未来”（不被扫描）；结果到齐后置 0（立即进入扫描）；
     * 收敛成功移除成员。O(N) keys 全量扫描 → O(logN) 定位 + 少量读取。
     */
    private static final String KEY_RECONCILER_ZSET = "agent:reconciler:plans";

    /** 全局并行工作流计数（Redis 原子计数，双实例共享，避免 acquire/release 跨实例错配） */
    private static final String KEY_ACTIVE = "agent:workflow:active";

    /** 原子获取许可：INCR 后若超过上限则回退，保证双实例合计不超过 max-concurrent */
    private static final RedisScript<Long> ACQUIRE_SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c > tonumber(ARGV[1]) then redis.call('DECR', KEYS[1]) return 0 end " +
            "redis.call('EXPIRE', KEYS[1], 300) return 1",
            Long.class);

    /** 原子释放许可（下限 0，防止异常场景计数为负） */
    private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
            "local c = redis.call('DECR', KEYS[1]) " +
            "if c < 0 then redis.call('SET', KEYS[1], 0) end " +
            "if c <= 0 then redis.call('DEL', KEYS[1]) end return 1",
            Long.class);

    private final TaskPlanner taskPlanner;
    private final SubTaskProducer subTaskProducer;
    private final StringRedisTemplate redisTemplate;
    private final BroadcastService broadcastService;
    private final LLMInvoker llmInvoker;
    private final LlmConfigProperties llmConfig;
    private final ObjectMapper objectMapper;
    private final ChatProcessor chatProcessor;
    private final ModelRouter modelRouter;

    @Value("${app.agent.planner.max-concurrent:8}")
    private int maxConcurrent;

    /** Reconciler 对账扫描周期（毫秒）：plan 注册进 ZSet 时的初始“未来”分数偏移 */
    @Value("${app.agent.planner.reconcile-interval-ms:30000}")
    private long reconcileIntervalMs;

    /** 收敛总结专用轻量模型（如 qwen-turbo）；留空则使用用户所选模型 */
    @Value("${app.agent.planner.converge-model:}")
    private String convergeModel;

    /** 收敛输出压缩：最终回答最大字数（≤0 表示不限制） */
    @Value("${app.agent.planner.converge-max-chars:1200}")
    private int convergeMaxChars;

    @Autowired
    public AgentWorkflowOrchestrator(TaskPlanner taskPlanner,
                                     SubTaskProducer subTaskProducer,
                                     StringRedisTemplate redisTemplate,
                                     BroadcastService broadcastService,
                                     LLMInvoker llmInvoker,
                                     LlmConfigProperties llmConfig,
                                     ObjectMapper objectMapper,
                                     @Lazy ChatProcessor chatProcessor,
                                     ModelRouter modelRouter) {
        this.taskPlanner = taskPlanner;
        this.subTaskProducer = subTaskProducer;
        this.redisTemplate = redisTemplate;
        this.broadcastService = broadcastService;
        this.llmInvoker = llmInvoker;
        this.llmConfig = llmConfig;
        this.objectMapper = objectMapper;
        this.chatProcessor = chatProcessor;
        this.modelRouter = modelRouter;
    }

    /**
     * 原子获取全局限流许可（Redis 计数，双实例共享）。
     *
     * @return true = 许可获取成功（工作流可启动）；false = 超过 max-concurrent，应降级普通流程
     */
    private boolean tryAcquirePermit(String reqId) {
        try {
            Long ok = redisTemplate.execute(ACQUIRE_SCRIPT,
                    java.util.List.of(KEY_ACTIVE), String.valueOf(maxConcurrent));
            return ok != null && ok == 1L;
        } catch (Exception e) {
            log.warn("[MultiAgent] req_id={} 限流计数异常，保守降级普通流程: {}", reqId, e.getMessage());
            return false;
        }
    }

    /** 释放全局限流许可（任意实例均可调用，Redis 原子计数保证不泄漏） */
    private void releasePermit(String planId) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, java.util.List.of(KEY_ACTIVE), "1");
            Long active = redisTemplate.opsForValue().get(KEY_ACTIVE) == null
                    ? 0L : Long.parseLong(redisTemplate.opsForValue().get(KEY_ACTIVE));
            log.info("[MultiAgent] 收敛结束释放限流许可 planId={} active={}", planId, active);
        } catch (Exception e) {
            log.warn("[MultiAgent] planId={} 限流计数释放异常: {}", planId, e.getMessage());
        }
    }

    /**
     * 尝试接管为并行工作流。
     *
     * @return true = 已接管（调用方应停止原流程）；false = 无需拆解，走原流程
     */
    public boolean tryParallelWorkflow(String reqId, Long userId, String question,
                                       ModelConfig config, double temperature) {
        if (question == null || question.isBlank()) return false;
        if (!taskPlanner.shouldDecompose(question)) return false;

        // 并发过载降级：同时运行的并行工作流达到全局上限（max-concurrent）时，
        // 放弃拆解、走普通流程（不排队、不拒绝，双实例 Redis 原子计数）
        if (!tryAcquirePermit(reqId)) {
            log.info("[MultiAgent] req_id={} 并发过载降级 maxConcurrent={} 许可已占满，走普通流程",
                    reqId, maxConcurrent);
            return false;
        }
        boolean started = false;
        try {
            SubAgentPlan plan = taskPlanner.buildPlan(question, config,
                    llmConfig.getBaseUrl(), llmConfig.getApiKey());
            if (plan == null) {
                log.info("[MultiAgent] req_id={} 计划生成失败，降级原流程", reqId);
                return false;
            }
            started = startWorkflow(reqId, userId, question, config, temperature, plan);
            return started;
        } catch (Exception e) {
            log.warn("[MultiAgent] req_id={} 工作流启动异常，降级原流程: {}", reqId, e.getMessage());
            return false;
        } finally {
            if (!started) {
                releasePermit("start-fail-" + reqId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  启动工作流
    // ═══════════════════════════════════════════════════════════════════

    private boolean startWorkflow(String reqId, Long userId, String question, ModelConfig config,
                                  double temperature, SubAgentPlan plan) {
        String planId = plan.planId;
        try {
            // 1. 元信息与计划写入 Redis（供任意实例收敛）
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reqId", reqId);
            meta.put("userId", userId);
            meta.put("question", question);
            meta.put("provider", config.provider);
            meta.put("model", config.model);
            meta.put("temperature", temperature);
            meta.put("startTime", System.currentTimeMillis());
            redisTemplate.opsForValue().set(key(KEY_PLAN, planId), objectMapper.writeValueAsString(plan), Duration.ofMinutes(30));
            redisTemplate.opsForValue().set(key(KEY_META, planId), objectMapper.writeValueAsString(meta), Duration.ofMinutes(30));
            redisTemplate.opsForValue().set(key(KEY_TOTAL, planId), String.valueOf(plan.tasks.size()), Duration.ofMinutes(30));
            redisTemplate.delete(key(KEY_RECEIVED, planId));
            redisTemplate.delete(key(KEY_LOCK, planId));

            // 1.5 注册到 Reconciler 扫描索引（ZSet）：score=下一次对账检查时间（未来），
            //     结果未到齐前不会被扫描；ResultCollector 收到结果后会刷新分数
            redisTemplate.opsForZSet().add(KEY_RECONCILER_ZSET, planId,
                    System.currentTimeMillis() + reconcileIntervalMs);
            redisTemplate.expire(KEY_RECONCILER_ZSET, Duration.ofMinutes(30));

            // 2. 推送计划到前端
            List<Map<String, Object>> taskBriefs = new ArrayList<>();
            for (SubAgentTask t : plan.tasks) {
                taskBriefs.add(Map.of("taskId", t.taskId, "title", t.title, "toolsScope", t.toolsScope));
            }
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.of(WsMessage.TYPE_PLAN_START).withReqId(reqId)
                            .with("planId", planId)
                            .with("title", plan.title)
                            .with("taskCount", plan.tasks.size())
                            .with("tasks", taskBriefs)
                            .toMap());

            // 3. 逐一分发子任务（RabbitMQ 公平分发到双实例 Worker）
            for (SubAgentTask task : plan.tasks) {
                subTaskProducer.sendTask(task);
            }
            log.info("[MultiAgent] req_id={} planId={} 已分发 {} 个子任务 tasks={}",
                    reqId, planId, plan.tasks.size(), plan.tasks.stream().map(t -> t.taskId).toList());
            return true;
        } catch (Exception e) {
            log.error("[MultiAgent] 工作流启动失败 planId={}: {}", planId, e.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.of(WsMessage.TYPE_PLAN_ERROR).withReqId(reqId)
                            .with("message", "任务并行拆分启动失败: " + e.getMessage()).toMap());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  结果收敛（由 SubTaskResultCollector 到齐后触发）
    // ═══════════════════════════════════════════════════════════════════

    /**
     * 全部子任务完成后，主 Agent 对结构化摘要进行最终总结并流式推送。
     */
    @SuppressWarnings("PMD.NPathComplexity") // 收敛编排多分支（限流/降级/轻量模型/硬截断/去重），逐条拆分反而降低可读性
    public void converge(String planId) {
        log.info("[MultiAgent] 收敛开始 planId={}", planId);
        try {
            Map<String, Object> meta = readMeta(planId);
            if (meta == null) {
                log.warn("[MultiAgent] 收敛失败 planId={} meta 缺失", planId);
                return;
            }
            String reqId = (String) meta.get("reqId");
            Long userId = Long.valueOf(String.valueOf(meta.get("userId")));
            String question = (String) meta.get("question");
            String provider = (String) meta.get("provider");
            String model = (String) meta.get("model");
            double temperature = ((Number) meta.get("temperature")).doubleValue();
            long startTime = ((Number) meta.get("startTime")).longValue();

            SubAgentPlan plan = readPlan(planId);
            List<SubAgentResult> results = readResults(planId);

            // 汇总消息：原始问题 + 每个子任务的标题与摘要
            StringBuilder sb = new StringBuilder();
            sb.append("你是主 Agent（指挥官）。你分派了 ").append(results.size())
                    .append(" 个子代理并行处理用户请求，以下是它们的结构化摘要。\n\n");
            sb.append("原始用户请求：\n").append(question).append("\n\n");
            int idx = 0;
            for (SubAgentResult r : results) {
                idx++;
                String title = findTaskTitle(plan, r.taskId);
                sb.append("【子任务 ").append(idx).append('】').append(title != null ? title : r.taskId).append('\n');
                if (r.success) {
                    sb.append("结果：").append(r.summary != null ? r.summary : "(无内容)").append('\n');
                } else {
                    sb.append("结果：该部分执行失败：").append(r.error).append('\n');
                }
                sb.append('\n');
            }
            // 给模型留 100 字余量，避免输出贴近上限
            int budget = convergeMaxChars > 0 ? Math.max(100, convergeMaxChars - 100) : 0;
            sb.append("请综合所有子结果，面向用户输出最终回答。输出要求：\n")
                    .append("1. 总字数控制在 ").append(budget > 0 ? budget : "不限")
                    .append(" 字以内（精简、要点化、直接给结论与建议）；\n")
                    .append("2. 用标题/编号分节组织，合并重复信息，剔除过程性描述与客套话；\n")
                    .append("3. 直接输出最终回答，不要解释过程。");

            ModelConfig config = resolveConvergeConfig(provider, model);
            // 配置了轻量收敛模型时优先使用（压缩输出 + 显著提速）
            if (convergeModel != null && !convergeModel.isBlank()) {
                ModelConfig fast = new ModelConfig();
                fast.provider = llmConfig.getProvider();
                fast.model = convergeModel;
                fast.apiKeyEncrypted = llmConfig.getApiKey();
                config = fast;
                log.info("[MultiAgent] 收敛使用轻量模型 provider={} model={}", fast.provider, fast.model);
            }

            // 流式总结并推送
            StringBuilder collector = new StringBuilder();
            final String topic = "/topic/user." + userId;
            String fullAnswer = llmInvoker.invokeStream(config, List.of(new LLMMessage("user", sb.toString())),
                    Math.min(0.6, Math.max(0.3, temperature)), "converge",
                    llmConfig.getBaseUrl(), llmConfig.getApiKey(),
                    token -> {
                        collector.append(token);
                        broadcastService.broadcast(topic,
                                WsMessage.streamToken(token).withReqId(reqId).toMap());
                    });
            String cleanAnswer = collector.isEmpty() ? fullAnswer : collector.toString();
            // 硬截断兜底：确保最终回答不超过 converge-max-chars
            if (convergeMaxChars > 0 && cleanAnswer.length() > convergeMaxChars) {
                cleanAnswer = cleanAnswer.substring(0, convergeMaxChars) + "……";
            }

            // 完成：持久化 + 缓存 + 记忆入库（复用 ChatProcessor 完整收尾）
            chatProcessor.completeWithAnswer(reqId, userId, question, cleanAnswer,
                    provider, model, startTime);
            // 收敛成功标记（30min）：供 Reconciler 去重。内部 API 请求可能无 DB 行，
            // 不能依赖 DB 状态判断收敛是否完成，此标记是主要去重依据
            redisTemplate.opsForValue().set(key(KEY_CONVERGED, planId), "1", Duration.ofMinutes(30));
            // 收敛成功 → 从 Reconciler 扫描索引移除（失败则保留 score=0，下轮对账继续重试）
            removeFromReconciler(planId);
            log.info("[MultiAgent] 收敛完成 planId={} tasks={} answerLen={}",
                    planId, results.size(), cleanAnswer.length());
        } catch (Exception e) {
            log.error("[MultiAgent] 收敛失败 planId={}: {}", planId, e.getMessage(), e);
            try {
                Map<String, Object> meta = readMeta(planId);
                if (meta != null) {
                    broadcastService.broadcast("/topic/user." + meta.get("userId"),
                            WsMessage.error("多Agent收敛失败: " + e.getMessage())
                                    .withReqId(String.valueOf(meta.get("reqId"))).toMap());
                }
            } catch (Exception ignored) {
            }
            // 包装重抛给 CoreBusinessMetricsAspect 统一记录 failed 指标（切面捕获后不重抛，对外语义不变）
            throw new ChatServiceException("多Agent收敛失败: " + e.getMessage(), e);
        } finally {
            // 释放全局限流许可（成功/失败/meta缺失均释放），Redis 原子计数保证不泄漏
            releasePermit(planId);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Redis 读写
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull") // null 表示"meta 不存在"，调用方依赖 null 判断
    private Map<String, Object> readMeta(String planId) {
        String json = redisTemplate.opsForValue().get(key(KEY_META, planId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private SubAgentPlan readPlan(String planId) {
        String json = redisTemplate.opsForValue().get(key(KEY_PLAN, planId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, SubAgentPlan.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private List<SubAgentResult> readResults(String planId) {
        List<SubAgentResult> results = new ArrayList<>();
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(KEY_RESULT_HASH, planId));
        for (Object v : entries.values()) {
            try {
                results.add(objectMapper.readValue(String.valueOf(v), SubAgentResult.class));
            } catch (JsonProcessingException e) {
                log.debug("[MultiAgent] 结果反序列化失败: {}", e.getMessage());
            }
        }
        return results;
    }

    private String findTaskTitle(SubAgentPlan plan, String taskId) {
        if (plan == null || plan.tasks == null) return null;
        for (SubAgentTask t : plan.tasks) {
            if (taskId.equals(t.taskId)) return t.title;
        }
        return null;
    }

    /**
     * 解析收敛使用的模型配置：优先取用户所选模型在 model_configs 中的真实 apiKey
     * （此前直接用 llmConfig.getApiKey() 会把默认 provider 的 key 错配到用户模型，导致 401）。
     */
    private ModelConfig resolveConvergeConfig(String provider, String model) {
        try {
            List<ModelConfig> all = modelRouter.loadChatModels(
                    llmConfig.getProvider(), llmConfig.getModel(), llmConfig.getApiKey());
            for (ModelConfig c : all) {
                if (provider != null && provider.equalsIgnoreCase(c.provider)
                        && model != null && model.equalsIgnoreCase(c.model)) {
                    return c;
                }
            }
        } catch (Exception e) {
            log.debug("[MultiAgent] 收敛模型配置解析失败，回退默认: {}", e.getMessage());
        }
        ModelConfig config = new ModelConfig();
        config.provider = provider;
        config.model = model;
        config.apiKeyEncrypted = llmConfig.getApiKey();
        return config;
    }

    private static String key(String fmt, String planId) {
        return String.format(fmt, planId);
    }

    /** 供 ResultCollector 使用的 Redis 键常量访问 */
    public static String keyPlan(String planId) { return key(KEY_PLAN, planId); }
    public static String keyMeta(String planId) { return key(KEY_META, planId); }
    public static String keyTotal(String planId) { return key(KEY_TOTAL, planId); }
    public static String keyReceived(String planId) { return key(KEY_RECEIVED, planId); }
    public static String keyResultHash(String planId) { return key(KEY_RESULT_HASH, planId); }
    public static String keyLock(String planId) { return key(KEY_LOCK, planId); }
    public static String keyConverged(String planId) { return key(KEY_CONVERGED, planId); }
    /** 供 Reconciler / ResultCollector 访问的扫描索引键 */
    public static String keyReconcilerZSet() { return KEY_RECONCILER_ZSET; }

    /** 收敛成功后将 planId 从 Reconciler 扫描索引移除（幂等，失败仅告警不阻塞） */
    private void removeFromReconciler(String planId) {
        try {
            redisTemplate.opsForZSet().remove(KEY_RECONCILER_ZSET, planId);
        } catch (Exception e) {
            log.warn("[MultiAgent] planId={} 移除 Reconciler 索引异常: {}", planId, e.getMessage());
        }
    }
}
