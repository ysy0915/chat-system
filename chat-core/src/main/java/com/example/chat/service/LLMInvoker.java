package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
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
    @Qualifier("taskTypeModelRouter")
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
    public String invoke(ModelConfig config, List<LLMMessage> messages,
                         double temperature, String scene,
                         String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 熔断检查：provider连续失败5次后快速失败，不发起LLM调用
        checkCircuitBreaker(config);

        long startTime = System.currentTimeMillis();
        TraceHolder trace = startTrace();
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = resolveApiKey(config, defaultApiKey);

        try {
            String answer = strategy.invoke(baseUrl, apiKey, config.model, messages, temperature);
            recordSuccess(scene, config, startTime, answer, trace);
            return answer;
        } catch (Exception e) {
            return handleFailure(config, messages, temperature, scene, defaultBaseUrl,
                    defaultApiKey, startTime, trace, e);
        } finally {
            clearTraceIfNeeded(trace);
        }
    }

    /**
     * 熔断检查：provider 连续失败达到阈值后快速失败，不发起 LLM 调用。
     * @param config 模型配置（取 provider 字段）
     * @throws RuntimeException 当 provider 已被熔断时抛出
     */
    private void checkCircuitBreaker(ModelConfig config) {
        if (circuitBreaker != null && !circuitBreaker.allowRequest(config.provider)) {
            throw new LLMCallException("LLM provider=" + config.provider + " 已熔断，请稍后重试");
        }
    }

    /**
     * 启动或复用当前线程的 traceId。
     * @return 持有 traceId 与是否由本方法启动的上下文对象
     */
    private TraceHolder startTrace() {
        boolean traceStarted = false;
        String traceId = null;
        if (traceContext != null) {
            traceId = traceContext.get();
            if (traceId == null) {
                traceId = traceContext.start();
                traceStarted = true;
            }
        }
        return new TraceHolder(traceId, traceStarted);
    }

    /**
     * 解析 API Key：config 显式配置优先，否则使用默认值。
     */
    private String resolveApiKey(ModelConfig config, String defaultApiKey) {
        return (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;
    }

    /**
     * 记录成功调用：熔断器记录成功、调用统计、日志、链路追踪。
     */
    private void recordSuccess(String scene, ModelConfig config, long startTime,
                               String answer, TraceHolder trace) {
        long latency = System.currentTimeMillis() - startTime;
        if (circuitBreaker != null) circuitBreaker.recordSuccess(config.provider);
        callRecorder.record(config.provider, config.model, scene, true, latency,
                answer != null ? answer.length() : 0);
        log.info("[LLMInvoker] {} provider={} model={} latency={}ms answerLen={}",
                scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
        recordTrace(trace.traceId, scene, config, startTime, latency, "SUCCESS", null);
    }

    /**
     * 处理调用失败：熔断器记录失败、调用统计、错误聚合、链路追踪、自愈重试。
     * @return 自愈成功则返回自愈结果，否则抛出原异常
     */
    private String handleFailure(ModelConfig config, List<LLMMessage> messages,
                                 double temperature, String scene, String defaultBaseUrl,
                                 String defaultApiKey, long startTime, TraceHolder trace,
                                 Exception e) throws Exception {
        long latency = System.currentTimeMillis() - startTime;
        if (circuitBreaker != null) circuitBreaker.recordFailure(config.provider);
        callRecorder.record(config.provider, config.model, scene, false, latency, 0);
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        log.error("[LLMInvoker] {} 调用失败 provider={} model={} latency={}ms error={} rootCause={}",
                scene, config.provider, config.model, latency, e.getMessage(), root.toString());
        ErrorType errorType = ErrorType.fromException(e);
        recordTrace(trace.traceId, scene, config, startTime, latency, "FAIL", e.getMessage());
        if (errorAggregator != null) {
            errorAggregator.recordError(scene, config.provider, config.model, errorType, e.getMessage());
        }
        return attemptSelfHealing(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, errorType, e);
    }

    /**
     * 尝试自愈重试：自愈组件未启用时直接抛出原异常。
     */
    private String attemptSelfHealing(ModelConfig config, List<LLMMessage> messages,
                                      double temperature, String scene, String defaultBaseUrl,
                                      String defaultApiKey, ErrorType errorType,
                                      Exception e) throws Exception {
        if (selfHealingService == null) {
            throw e;
        }
        try {
            log.info("[LLMInvoker] 触发自愈重试 scene={} errorType={}", scene, errorType);
            return selfHealingService.healAndRetry(config, messages, temperature, scene,
                    defaultBaseUrl, defaultApiKey, e);
        } catch (Exception healEx) {
            log.warn("[LLMInvoker] 自愈重试失败 scene={} error={}", scene, healEx.getMessage());
            throw healEx;
        }
    }

    /**
     * 清理本次启动的 trace 上下文。
     */
    private void clearTraceIfNeeded(TraceHolder trace) {
        if (trace.traceStarted && traceContext != null) {
            traceContext.clear();
        }
    }

    /**
     * trace 上下文持有者：封装 traceId 与是否由本次调用启动。
     */
    private static final class TraceHolder {
        final String traceId;
        final boolean traceStarted;
        TraceHolder(String traceId, boolean traceStarted) {
            this.traceId = traceId;
            this.traceStarted = traceStarted;
        }
    }

    /**
     * 流式调用
     * @param config 模型配置
     * @param messages 消息列表
     * @param temperature 温度
     * @param scene 调用场景
     * @param defaultBaseUrl 默认 baseUrl（config 中无 meta 时使用）
     * @param defaultApiKey 默认 API Key
     * @param callback 流式回调（每收到一段文本触发）
     * @return 完整回答
     */
    public String invokeStream(ModelConfig config, List<LLMMessage> messages,
                               double temperature, String scene,
                               String defaultBaseUrl, String defaultApiKey,
                               Consumer<String> callback) throws Exception {
        // 熔断检查
        checkCircuitBreaker(config);

        long startTime = System.currentTimeMillis();
        TraceHolder trace = startTrace();
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = resolveApiKey(config, defaultApiKey);

        try {
            String answer = strategy.invokeStream(baseUrl, apiKey, config.model, messages, temperature, callback);
            recordStreamSuccess(scene, config, startTime, answer, trace);
            return answer;
        } catch (Exception e) {
            return handleStreamFailure(config, messages, temperature, scene, defaultBaseUrl,
                    defaultApiKey, startTime, trace, e);
        } finally {
            clearTraceIfNeeded(trace);
        }
    }

    /**
     * 记录流式调用成功：熔断器记录成功、调用统计、日志、链路追踪。
     */
    private void recordStreamSuccess(String scene, ModelConfig config, long startTime,
                                     String answer, TraceHolder trace) {
        long latency = System.currentTimeMillis() - startTime;
        if (circuitBreaker != null) circuitBreaker.recordSuccess(config.provider);
        callRecorder.record(config.provider, config.model, scene, true, latency,
                answer != null ? answer.length() : 0);
        log.info("[LLMInvoker] stream:{} provider={} model={} latency={}ms answerLen={}",
                scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
        recordTrace(trace.traceId, scene, config, startTime, latency, "SUCCESS", null);
    }

    /**
     * 处理流式调用失败：熔断器记录失败、调用统计、错误聚合、链路追踪、自愈重试。
     * @return 自愈成功则返回自愈结果，否则抛出原异常
     */
    private String handleStreamFailure(ModelConfig config, List<LLMMessage> messages,
                                       double temperature, String scene, String defaultBaseUrl,
                                       String defaultApiKey, long startTime, TraceHolder trace,
                                       Exception e) throws Exception {
        long latency = System.currentTimeMillis() - startTime;
        if (circuitBreaker != null) circuitBreaker.recordFailure(config.provider);
        callRecorder.record(config.provider, config.model, scene, false, latency, 0);
        log.error("[LLMInvoker] stream:{} 调用失败 provider={} model={} latency={}ms error={}",
                scene, config.provider, config.model, latency, e.getMessage());
        ErrorType errorType = ErrorType.fromException(e);
        recordTrace(trace.traceId, scene, config, startTime, latency, "FAIL", e.getMessage());
        if (errorAggregator != null) {
            errorAggregator.recordError(scene, config.provider, config.model, errorType, e.getMessage());
        }
        return attemptStreamSelfHealing(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, errorType, e);
    }

    /**
     * 尝试流式自愈重试：自愈组件未启用时直接抛出原异常。
     */
    private String attemptStreamSelfHealing(ModelConfig config, List<LLMMessage> messages,
                                            double temperature, String scene, String defaultBaseUrl,
                                            String defaultApiKey, ErrorType errorType,
                                            Exception e) throws Exception {
        if (selfHealingService == null) {
            throw e;
        }
        try {
            log.info("[LLMInvoker] stream 触发自愈重试 scene={} errorType={}", scene, errorType);
            return selfHealingService.healAndRetry(config, messages, temperature, scene,
                    defaultBaseUrl, defaultApiKey, e);
        } catch (Exception healEx) {
            log.warn("[LLMInvoker] stream 自愈重试失败 scene={} error={}", scene, healEx.getMessage());
            throw healEx;
        }
    }

    /**
     * 简化版非流式调用（单条 prompt）
     */
    public String invoke(ModelConfig config, String prompt,
                         double temperature, String scene,
                         String defaultBaseUrl, String defaultApiKey) throws Exception {
        List<LLMMessage> messages = List.of(
                LLMMessage.user(prompt)
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
                                    List<LLMMessage> messages, double temperature,
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
     * 记录调用链路（可观测性组件未启用时跳过）。
     * @param traceId 链路 ID（为 null 时使用空串）
     * @param scene 调用场景
     * @param config 模型配置
     * @param startTime 调用起始时间戳（ms）
     * @param latency 调用耗时（ms）
     * @param status 调用状态（SUCCESS / FAIL）
     * @param errorMessage 失败时的错误信息（成功时为 null）
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
