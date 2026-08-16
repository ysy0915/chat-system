package com.example.chat.service;

import com.example.chat.client.LlmBundleClient;
import com.example.chat.config.BundleLlmProperties;
import com.example.chat.dto.LangChainRequest;
import com.example.chat.dto.LangChainResponse;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.observability.CallTrace;
import com.example.chat.observability.CircuitBreaker;
import com.example.chat.observability.ErrorAggregator;
import com.example.chat.observability.ErrorType;
import com.example.chat.observability.SelfHealingService;
import com.example.chat.observability.TraceContext;
import com.example.chat.observability.TraceRecorder;
import com.example.chat.util.ApiKeyResolver;
import com.example.chat.util.BaseUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
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

    private final BaseUrlResolver baseUrlResolver;
    private final LLMCallRecorder callRecorder;
    private final DirectLLMClient directLLMClient;

    @Autowired(required = false)
    private TraceRecorder traceRecorder;

    @Autowired(required = false)
    private ErrorAggregator errorAggregator;

    @Autowired(required = false)
    private SelfHealingService selfHealingService;

    @Autowired(required = false)
    private TraceContext traceContext;

    /** 熔断器 */
    @Autowired(required = false)
    private CircuitBreaker circuitBreaker;

    /** LLM Bundle 统一网关客户端（可选注入，app.llm.bundle.enabled=true 时启用） */
    @Autowired(required = false)
    private LlmBundleClient llmBundleClient;

    /** LLM Bundle 接入配置 */
    @Autowired(required = false)
    private BundleLlmProperties bundleLlmProperties;

    public LLMInvoker(BaseUrlResolver baseUrlResolver,
                       LLMCallRecorder callRecorder,
                       DirectLLMClient directLLMClient) {
        this.baseUrlResolver = baseUrlResolver;
        this.callRecorder = callRecorder;
        this.directLLMClient = directLLMClient;
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
        return invoke(config, messages, temperature, scene, defaultBaseUrl, defaultApiKey, null);
    }

    /**
     * 非流式调用（可指定 maxTokens；为 null 时不限制）。
     */
    public String invoke(ModelConfig config, List<LLMMessage> messages,
                         double temperature, String scene,
                         String defaultBaseUrl, String defaultApiKey,
                         Integer maxTokens) throws Exception {
        // 熔断检查：provider连续失败5次后快速失败，不发起LLM调用
        checkCircuitBreaker(config);

        long startTime = System.currentTimeMillis();
        TraceHolder trace = startTrace();

        // === LLM Bundle 模式：优先走 chat-llm 统一网关，失败自动回退直连 ===
        if (bundleEnabled()) {
            try {
                LangChainResponse resp = invokeViaBundle(config, messages, temperature, scene,
                        defaultBaseUrl, defaultApiKey, trace.traceId, maxTokens);
                if (resp.isSuccess()) {
                    recordSuccess(scene, config, startTime, resp.getContent(), trace);
                    clearTraceIfNeeded(trace);
                    return resp.getContent();
                }
                log.warn("[LLMInvoker] bundle 调用失败, 回退直连 scene={} provider={} error={}",
                        scene, config.provider, resp.getError());
            } catch (Exception e) {
                log.warn("[LLMInvoker] bundle 调用异常, 回退直连 scene={} provider={} error={}",
                        scene, config.provider, e.getMessage());
            }
        }

        // Bundle 未启用或调用失败：降级 DirectLLMClient 直连兜底
        try {
            String answer = invokeDirect(config, messages, temperature, defaultBaseUrl, defaultApiKey, null, maxTokens);
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
     * 解析 API Key：config 显式配置（DB）优先，否则使用默认值（环境变量兜底）。
     */
    private String resolveApiKey(ModelConfig config, String defaultApiKey) {
        return ApiKeyResolver.resolve(config, defaultApiKey);
    }

    /**
     * DirectLLMClient 直连兜底（OpenAI /chat/completions 兼容接口）。
     * streamCallback 为 null 时走非流式；非空时取完整答案后一次性回调（模拟流式降级）。
     */
    private String invokeDirect(ModelConfig config, List<LLMMessage> messages,
                                double temperature, String defaultBaseUrl, String defaultApiKey,
                                Consumer<String> streamCallback, Integer maxTokens) {
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = resolveApiKey(config, defaultApiKey);
        int mt = (maxTokens != null && maxTokens > 0) ? maxTokens : -1;
        String answer = directLLMClient.call(baseUrl, apiKey, config.model, messages, temperature, mt);
        if (streamCallback != null && answer != null && !answer.isEmpty()) {
            streamCallback.accept(answer);
        }
        return answer;
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

    // ============ LLM Bundle (chat-llm 统一网关) 支持 ============

    /**
     * bundle 模式是否启用：客户端与配置注入齐备且显式开启。
     */
    private boolean bundleEnabled() {
        return llmBundleClient != null && bundleLlmProperties != null
                && bundleLlmProperties.isEnabled();
    }

    /**
     * 构造发送给 chat-llm 的 LangChainRequest。
     * ModelConfig 动态配置的 baseUrl/apiKey 通过 extra 透传（chat-llm 侧消费覆盖）。
     */
    @SuppressWarnings("PMD.UnusedFormalParameter") // scene 预留：后续可做场景差异化 extra 透传
    private LangChainRequest buildBundleRequest(ModelConfig config, List<LLMMessage> messages,
                                                double temperature, String scene,
                                                String defaultBaseUrl, String defaultApiKey,
                                                String traceId, Integer maxTokens) {
        LangChainRequest req = new LangChainRequest();
        req.setProvider(config.provider);
        req.setModel(config.model);
        req.setMessages(LLMMessage.toMapList(messages));
        req.setTemperature(temperature);
        if (maxTokens != null && maxTokens > 0) req.setMaxTokens(maxTokens);
        req.setBizType("CHAT");
        req.setTraceId(traceId);
        Map<String, Object> extra = new HashMap<>();
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = resolveApiKey(config, defaultApiKey);
        if (baseUrl != null && !baseUrl.isBlank()) extra.put("baseUrl", baseUrl);
        if (apiKey != null && !apiKey.isBlank()) extra.put("apiKey", apiKey);
        // 关闭豆包 seed-2.0 系列默认开启的深度思考（reasoning）：
        // 该系列模型每次回答前会先生成大量思考 token（实测 149 个），导致首 token 延迟 4~18 秒。
        // 关闭 thinking 后首 token 稳定在 ~2.2 秒（2026-08-17 实测）。
        if ("doubao".equalsIgnoreCase(config.provider)) {
            extra.put("thinking", Map.of("type", "disabled"));
        }
        if (!extra.isEmpty()) req.setExtra(extra);
        return req;
    }

    private LangChainResponse invokeViaBundle(ModelConfig config, List<LLMMessage> messages,
                                              double temperature, String scene,
                                              String defaultBaseUrl, String defaultApiKey,
                                              String traceId, Integer maxTokens) {
        LangChainRequest req = buildBundleRequest(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, traceId, maxTokens);
        return llmBundleClient.invoke(req);
    }

    private LangChainResponse invokeViaBundleStream(ModelConfig config, List<LLMMessage> messages,
                                                    double temperature, String scene,
                                                    String defaultBaseUrl, String defaultApiKey,
                                                    String traceId, Consumer<String> callback) {
        return invokeViaBundleStream(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, traceId, callback, null);
    }

    private LangChainResponse invokeViaBundleStream(ModelConfig config, List<LLMMessage> messages,
                                                    double temperature, String scene,
                                                    String defaultBaseUrl, String defaultApiKey,
                                                    String traceId, Consumer<String> callback,
                                                    Consumer<String> reasoningCallback) {
        LangChainRequest req = buildBundleRequest(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, traceId, null);
        // 思考链透传：仅在需要 reasoning 回调时开启，避免普通场景引入额外解析
        req.setStreamReasoning(reasoningCallback != null);
        return llmBundleClient.invokeStream(req, callback, reasoningCallback);
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
        return invokeStream(config, messages, temperature, scene,
                defaultBaseUrl, defaultApiKey, callback, null);
    }

    /**
     * 流式调用（思考链透传重载）
     * @param config 模型配置
     * @param messages 消息列表
     * @param temperature 温度
     * @param scene 调用场景
     * @param defaultBaseUrl 默认 baseUrl（config 中无 meta 时使用）
     * @param defaultApiKey 默认 API Key
     * @param callback 流式回调（每收到一段回答文本触发）
     * @param reasoningCallback 思考过程回调（原生 reasoning_content，经 {@code \u0001R:} 前缀透传；可为 null）
     * @return 完整回答（不含思考过程）
     */
    public String invokeStream(ModelConfig config, List<LLMMessage> messages,
                               double temperature, String scene,
                               String defaultBaseUrl, String defaultApiKey,
                               Consumer<String> callback,
                               Consumer<String> reasoningCallback) throws Exception {
        // 熔断检查
        checkCircuitBreaker(config);

        long startTime = System.currentTimeMillis();
        TraceHolder trace = startTrace();

        // === LLM Bundle 模式：优先走 chat-llm 统一网关，失败自动回退直连 ===
        if (bundleEnabled()) {
            try {
                LangChainResponse resp = invokeViaBundleStream(config, messages, temperature, scene,
                        defaultBaseUrl, defaultApiKey, trace.traceId, callback, reasoningCallback);
                if (resp.isSuccess()) {
                    recordStreamSuccess(scene, config, startTime, resp.getContent(), trace);
                    clearTraceIfNeeded(trace);
                    return resp.getContent();
                }
                log.warn("[LLMInvoker] bundle stream 失败, 回退直连 scene={} provider={} error={}",
                        scene, config.provider, resp.getError());
            } catch (Exception e) {
                log.warn("[LLMInvoker] bundle stream 异常, 回退直连 scene={} provider={} error={}",
                        scene, config.provider, e.getMessage());
            }
        }

        // Bundle 未启用或调用失败：降级 DirectLLMClient 直连兜底（完整答案一次性回调）
        try {
            String answer = invokeDirect(config, messages, temperature, defaultBaseUrl, defaultApiKey, callback, null);
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
