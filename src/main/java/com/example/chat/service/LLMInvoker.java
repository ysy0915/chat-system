package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.factory.LLMStrategyFactory;
import com.example.chat.strategy.LLMStrategy;
import com.example.chat.util.BaseUrlResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class LLMInvoker {

    private static final Logger log = LoggerFactory.getLogger(LLMInvoker.class);

    private final LLMStrategyFactory strategyFactory;
    private final BaseUrlResolver baseUrlResolver;
    private final LLMCallRecorder callRecorder;

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
        long startTime = System.currentTimeMillis();
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        try {
            String answer = strategy.invoke(baseUrl, apiKey, config.model, messages, temperature);
            long latency = System.currentTimeMillis() - startTime;
            callRecorder.record(config.provider, config.model, scene, true, latency,
                    answer != null ? answer.length() : 0);
            log.info("[LLMInvoker] {} provider={} model={} latency={}ms answerLen={}",
                    scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
            return answer;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            callRecorder.record(config.provider, config.model, scene, false, latency, 0);
            log.error("[LLMInvoker] {} 调用失败 provider={} model={} latency={}ms error={}",
                    scene, config.provider, config.model, latency, e.getMessage());
            throw e;
        }
    }

    /**
     * 流式调用
     */
    public String invokeStream(ModelConfig config, List<Map<String, Object>> messages,
                               double temperature, String scene,
                               String defaultBaseUrl, String defaultApiKey,
                               Consumer<String> callback) throws Exception {
        long startTime = System.currentTimeMillis();
        LLMStrategy strategy = strategyFactory.getStrategy(config.provider);
        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        try {
            String answer = strategy.invokeStream(baseUrl, apiKey, config.model, messages, temperature, callback);
            long latency = System.currentTimeMillis() - startTime;
            callRecorder.record(config.provider, config.model, scene, true, latency,
                    answer != null ? answer.length() : 0);
            log.info("[LLMInvoker] stream:{} provider={} model={} latency={}ms answerLen={}",
                    scene, config.provider, config.model, latency, answer != null ? answer.length() : 0);
            return answer;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            callRecorder.record(config.provider, config.model, scene, false, latency, 0);
            log.error("[LLMInvoker] stream:{} 调用失败 provider={} model={} latency={}ms error={}",
                    scene, config.provider, config.model, latency, e.getMessage());
            throw e;
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
}
