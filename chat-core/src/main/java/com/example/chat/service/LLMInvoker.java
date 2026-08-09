package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.factory.LLMStrategyFactory;
import com.example.chat.observability.CallTrace;
import com.example.chat.observability.CircuitBreaker;
import com.example.chat.observability.ErrorAggregator;
import com.example.chat.observability.ErrorType;
import com.example.chat.observability.SelfHealingService;
import com.example.chat.observability.TraceContext;
import com.example.chat.observability.TraceRecorder;
import com.example.chat.router.ModelRouter;
import com.example.chat.router.RoutingDecision;
import com.example.chat.router.TaskClassifier;
import com.example.chat.router.TaskType;
import com.example.chat.strategy.LLMStrategy;
import com.example.chat.util.BaseUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 统一调用入口
 * 封装策略选择、baseUrl 解析、API Key 处理、调用统计等公共逻辑
 * 替代 ChatProcessor / DebateProcessor / TreeHoleService / ModelAutoChatService 中重复的 LLM 调用代码
 */
@Service
@ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class LLMInvoker {

    private static final Logger log = LoggerFactory.getLogger(LLMInvoker.class);

    private final LLMStrategyFactory strategyFactory;
    private final BaseUrlResolver baseUrlResolver;
    private final LLMCallRecorder callRecorder;

    @Autowired(required = false)
    private TraceRecorder traceRecorder;

    @Autowired(required = false)
    private ErrorAggregator errorAggregator;

    @Autowired(required = false)
    private SelfHealingService selfHealingService;

    @Autowired(required = false)
    private TraceContext traceContext;

    /** 任务分类器（可选注入，app.classifier.enabled=true 时启用） */
    @Autowired(required = false)
    private TaskClassifier taskClassifier;

    /** 模型路由器（可选注入，app.router.enabled=true 时启用） */
    @Autowired(required = false)
    private ModelRouter modelRouter;

    /** 熔断器 */
    @Autowired(required = false)
    private CircuitBreaker circuitBreaker;

    public LLMInvoker(LLMStrategyFactory strategyFactory,
                       BaseUrlResolver baseUrlResolver,
                       LLMCallRecorder callRecorder) {
        this.strategyFactory = strategyFactory;
        this.baseUrlResolver = baseUrlResolver;
        this.callRecorder = callRecorder;
    }

    /**
     * 非流式调用
     * @param config 模型配置
     * @param messages 消息列表
     * @param temperature 温度
     * @param scene 调用场景（chat/debate/treehole/auto/personal）
     * @param defaultBaseUrl 默认 baseUrl（config 中无 meta 时使用）
     * @param defaultApiKey 默认 API Key
     * @return 完整回答
     */
    public String invoke(ModelConfig config, List<Map<String, Object>> messages,
                         double temperature, String scene,
                         String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 熔断检查：provider连续失败5次后快速失败，不发起LLM调用
        if (circuitBreaker != null && !circuitBreaker.allowRequest(config.provider)) {
            throw new RuntimeException("LLM provider=" + config.provider + " 已熔断，请稍后重试");
        }
        long startTime = System.currentTimeMillis();
        // 生成 traceId（若当前线程未开启）
        boolean traceStarted = false;
        String traceId = null;
        if (traceContext != null) {
            traceId = traceContext.get();
            if (traceId == null) {
                traceId = traceContext.start();
                traceStarted = true;
            }
        }
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        try {
            String answer = strategy.invoke(baseUrl, apiKey, config.model, messages, temperature);
            long latency = System.currentTimeMillis() - startTime;
            if (circuitBreaker != null) circuitBreaker.recordSuccess(config.provider);
            callRecorder.record(config.provider, config.model, scene, true, latency,
                    answer != null ? answer.length() : 0);
            log.info("[LLMInvoker] {} provider={} model={} latency={}ms answerLen={}",
                    scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
            recordTrace(traceId, scene, config, startTime, latency, "SUCCESS", null);
            return answer;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            if (circuitBreaker != null) circuitBreaker.recordFailure(config.provider);
            callRecorder.record(config.provider, config.model, scene, false, latency, 0);
            log.error("[LLMInvoker] {} 调用失败 provider={} model={} latency={}ms error={}",
                    scene, config.provider, config.model, latency, e.getMessage());
            ErrorType errorType = ErrorType.fromException(e);
            recordTrace(traceId, scene, config, startTime, latency, "FAIL", e.getMessage());
            if (errorAggregator != null) {
                errorAggregator.recordError(scene, config.provider, config.model, errorType, e.getMessage());
            }
            // 尝试自愈重试
            if (selfHealingService != null) {
                try {
                    log.info("[LLMInvoker] 触发自愈重试 scene={} errorType={}", scene, errorType);
                    String healed = selfHealingService.healAndRetry(config, messages, temperature, scene,
                            defaultBaseUrl, defaultApiKey, e);
                    return healed;
                } catch (Exception healEx) {
                    log.warn("[LLMInvoker] 自愈重试失败 scene={} error={}", scene, healEx.getMessage());
                    throw healEx;
                }
            }
            throw e;
        } finally {
            if (traceStarted && traceContext != null) {
                traceContext.clear();
            }
        }
    }

    /**
     * 流式调用
     */
    public String invokeStream(ModelConfig config, List<Map<String, Object>> messages,
                               double temperature, String scene,
                               String defaultBaseUrl, String defaultApiKey,
                               Consumer<String> callback) throws Exception {
        // 熔断检查
        if (circuitBreaker != null && !circuitBreaker.allowRequest(config.provider)) {
            throw new RuntimeException("LLM provider=" + config.provider + " 已熔断，请稍后重试");
        }
        long startTime = System.currentTimeMillis();
        boolean traceStarted = false;
        String traceId = null;
        if (traceContext != null) {
            traceId = traceContext.get();
            if (traceId == null) {
                traceId = traceContext.start();
                traceStarted = true;
            }
        }
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        try {
            String answer = strategy.invokeStream(baseUrl, apiKey, config.model, messages, temperature, callback);
            long latency = System.currentTimeMillis() - startTime;
            if (circuitBreaker != null) circuitBreaker.recordSuccess(config.provider);
            callRecorder.record(config.provider, config.model, scene, true, latency,
                    answer != null ? answer.length() : 0);
            log.info("[LLMInvoker] stream:{} provider={} model={} latency={}ms answerLen={}",
                    scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
            recordTrace(traceId, scene, config, startTime, latency, "SUCCESS", null);
            return answer;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            if (circuitBreaker != null) circuitBreaker.recordFailure(config.provider);
            callRecorder.record(config.provider, config.model, scene, false, latency, 0);
            log.error("[LLMInvoker] stream:{} 调用失败 provider={} model={} latency={}ms error={}",
                    scene, config.provider, config.model, latency, e.getMessage());
            ErrorType errorType = ErrorType.fromException(e);
            recordTrace(traceId, scene, config, startTime, latency, "FAIL", e.getMessage());
            if (errorAggregator != null) {
                errorAggregator.recordError(scene, config.provider, config.model, errorType, e.getMessage());
            }
            if (selfHealingService != null) {
                try {
                    log.info("[LLMInvoker] stream 触发自愈重试 scene={} errorType={}", scene, errorType);
                    String healed = selfHealingService.healAndRetry(config, messages, temperature, scene,
                            defaultBaseUrl, defaultApiKey, e);
                    return healed;
                } catch (Exception healEx) {
                    log.warn("[LLMInvoker] stream 自愈重试失败 scene={} error={}", scene, healEx.getMessage());
                    throw healEx;
                }
            }
            throw e;
        } finally {
            if (traceStarted && traceContext != null) {
                traceContext.clear();
            }
        }
    }

    /**
     * 简化版非流式调用（单条 prompt）
     */
    public String invoke(ModelConfig config, String prompt,
                         double temperature, String scene,
                         String defaultBaseUrl, String defaultApiKey) throws Exception {
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        return invoke(config, messages, temperature, scene, defaultBaseUrl, defaultApiKey);
    }

    /**
     * 动态路由调用入口
     * 流程：
     *   a. classifier.classify(userInput, scene) → TaskType
     *   b. router.route(taskType, scene, preferredModelId) → ModelConfig
     *   c. 记录路由决策日志
     *   d. 调用原有 invoke 方法
     *
     * 当 app.router.enabled=false 或 app.classifier.enabled=false 时，
     * 路由组件未注入，此时方法降级为：使用 preferredModelId 或抛出异常提示未开启动态路由。
     *
     * @param userInput        用户原始输入（用于任务分类）
     * @param scene            业务场景
     * @param preferredModelId 用户偏好模型 ID（可为 null）
     * @param messages         消息列表
     * @param temperature      温度
     * @param defaultBaseUrl   默认 baseUrl
     * @param defaultApiKey    默认 API Key
     * @return 完整回答
     */
    public String invokeWithRouting(String userInput, String scene, Long preferredModelId,
                                    List<Map<String, Object>> messages, double temperature,
                                    String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 路由组件未启用：直接抛出明确异常，避免误调用
        if (taskClassifier == null || modelRouter == null) {
            throw new IllegalStateException("动态路由未启用，请设置 app.router.enabled=true 和 app.classifier.enabled=true");
        }

        // a. 任务分类
        TaskType taskType = taskClassifier.classify(userInput, scene);

        // b. 模型路由
        RoutingDecision decision = modelRouter.route(taskType, scene, preferredModelId);
        if (decision == null || decision.selectedConfig == null) {
            throw new IllegalStateException("动态路由未找到可用模型，taskType=" + taskType);
        }
        ModelConfig config = decision.selectedConfig;

        // c. 记录路由决策日志
        log.info("[LLMInvoker] routing scene={} taskType={} -> model={} provider={} reason={} decision={}",
                scene, taskType, decision.selectedModel, decision.selectedProvider,
                decision.reason, decision.toJson());

        // d. 调用原有 invoke 方法（保持兼容，沿用现有统计/链路/自愈逻辑）
        return invoke(config, messages, temperature, scene, defaultBaseUrl, defaultApiKey);
    }

    /**
     * 记录调用链路（可观测性组件未启用时跳过）
     */
    private void recordTrace(String traceId, String scene, ModelConfig config,
                             long startTime, long latency, String status, String errorMessage) {
        if (traceRecorder == null) return;
        try {
            CallTrace trace = new CallTrace(
                    traceId != null ? traceId : "",
                    scene,
                    config.provider,
                    config.model,
                    startTime,
                    startTime + latency,
                    latency,
                    status,
                    errorMessage,
                    null
            );
            traceRecorder.record(trace);
        } catch (Exception e) {
            log.warn("[LLMInvoker] 记录链路失败: {}", e.getMessage());
        }
    }
}
