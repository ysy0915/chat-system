package com.example.chat.observability;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.LLMInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 错误自愈服务
 * 根据错误类型采用不同重试策略，最多重试 2 次
 */
@Service
@ConditionalOnProperty(name = "app.observability.enabled", havingValue = "true", matchIfMissing = true)
public class SelfHealingService {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingService.class);

    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private LLMInvoker llmInvoker;

    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Autowired
    private ErrorAggregator errorAggregator;

    @Value("${app.observability.auto-heal:true}")
    private boolean autoHealEnabled;

    private static final int MAX_RETRY = 2;

    /**
     * 自愈重试入口
     * @param failedConfig 失败的模型配置
     * @param messages 消息列表
     * @param temp temperature
     * @param scene 调用场景
     * @param defaultBaseUrl 默认 baseUrl
     * @param defaultApiKey 默认 API Key
     * @param lastError 上次失败的异常
     * @return 重试成功的回答；无法自愈时抛出原异常
     */
    public String healAndRetry(ModelConfig failedConfig, List<Map<String, Object>> messages,
                               double temp, String scene,
                               String defaultBaseUrl, String defaultApiKey,
                               Exception lastError) throws Exception {
        if (!autoHealEnabled) {
            throw lastError;
        }

        ErrorType errorType = ErrorType.fromException(lastError);
        log.info("[SelfHealing] 触发自愈 scene={} provider={} model={} errorType={} error={}",
                scene, failedConfig.provider, failedConfig.model, errorType, lastError.getMessage());

        switch (errorType) {
            case RATE_LIMIT:
            case TIMEOUT:
                // 换一个同 provider 的其他模型重试
                return retryWithAlternateModel(failedConfig, messages, temp, scene, defaultBaseUrl, defaultApiKey, lastError, "RATE_LIMIT/TIMEOUT");
            case AUTH_FAILED:
                // 跳过该模型，用默认模型重试
                return retryWithDefaultModel(failedConfig, messages, temp, scene, defaultBaseUrl, defaultApiKey, lastError, "AUTH_FAILED");
            case NETWORK_ERROR:
                // 等待 1 秒后重试一次
                return retryAfterDelay(failedConfig, messages, temp, scene, defaultBaseUrl, defaultApiKey, lastError);
            case PARSE_ERROR:
                // 降低 temperature 到 0.3 重试
                return retryWithLowerTemp(failedConfig, messages, scene, defaultBaseUrl, defaultApiKey, lastError, temp);
            case MODEL_NOT_FOUND:
            case UNKNOWN:
            default:
                // 不重试，直接抛出
                log.info("[SelfHealing] errorType={} 不重试，直接抛出", errorType);
                throw lastError;
        }
    }

    /**
     * 策略 a：换一个同 provider 的其他模型重试
     */
    private String retryWithAlternateModel(ModelConfig failedConfig, List<Map<String, Object>> messages,
                                           double temp, String scene,
                                           String defaultBaseUrl, String defaultApiKey,
                                           Exception lastError, String reason) throws Exception {
        List<ModelConfig> candidates = modelConfigRepository.findAllEnabledByType("chat");
        int retried = 0;
        for (ModelConfig candidate : candidates) {
            if (retried >= MAX_RETRY) break;
            // 跳过失败模型本身，选择同 provider 的其他模型
            if (candidate.id.equals(failedConfig.id)) continue;
            if (failedConfig.provider != null && !failedConfig.provider.equalsIgnoreCase(candidate.provider)) {
                continue;
            }
            retried++;
            log.info("[SelfHealing] {} 第{}次重试：切换到 model={}", reason, retried, candidate.model);
            try {
                String answer = llmInvoker.invoke(candidate, messages, temp, scene, defaultBaseUrl, defaultApiKey);
                log.info("[SelfHealing] 重试成功 model={}", candidate.model);
                return answer;
            } catch (Exception e) {
                log.warn("[SelfHealing] 重试失败 model={} error={}", candidate.model, e.getMessage());
                errorAggregator.recordError(scene, candidate.provider, candidate.model,
                        ErrorType.fromException(e), e.getMessage());
            }
        }
        log.warn("[SelfHealing] {} 无可用同 provider 备选模型或重试用尽", reason);
        throw lastError;
    }

    /**
     * 策略 b：跳过失败模型，用默认模型（优先级最高）重试
     */
    private String retryWithDefaultModel(ModelConfig failedConfig, List<Map<String, Object>> messages,
                                         double temp, String scene,
                                         String defaultBaseUrl, String defaultApiKey,
                                         Exception lastError, String reason) throws Exception {
        List<ModelConfig> candidates = modelConfigRepository.findAllEnabledByType("chat");
        int retried = 0;
        for (ModelConfig candidate : candidates) {
            if (retried >= MAX_RETRY) break;
            // 跳过失败模型，选其他模型（不限 provider）
            if (candidate.id.equals(failedConfig.id)) continue;
            retried++;
            log.info("[SelfHealing] {} 第{}次重试：切换到 provider={} model={}", reason, retried, candidate.provider, candidate.model);
            try {
                String answer = llmInvoker.invoke(candidate, messages, temp, scene, defaultBaseUrl, defaultApiKey);
                log.info("[SelfHealing] 重试成功 provider={} model={}", candidate.provider, candidate.model);
                return answer;
            } catch (Exception e) {
                log.warn("[SelfHealing] 重试失败 provider={} model={} error={}", candidate.provider, candidate.model, e.getMessage());
                errorAggregator.recordError(scene, candidate.provider, candidate.model,
                        ErrorType.fromException(e), e.getMessage());
            }
        }
        log.warn("[SelfHealing] {} 无可用备选模型或重试用尽", reason);
        throw lastError;
    }

    /**
     * 策略 c：等待 1 秒后重试一次
     */
    private String retryAfterDelay(ModelConfig failedConfig, List<Map<String, Object>> messages,
                                   double temp, String scene,
                                   String defaultBaseUrl, String defaultApiKey,
                                   Exception lastError) throws Exception {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        log.info("[SelfHealing] NETWORK_ERROR 等待1秒后重试 model={}", failedConfig.model);
        try {
            String answer = llmInvoker.invoke(failedConfig, messages, temp, scene, defaultBaseUrl, defaultApiKey);
            log.info("[SelfHealing] 重试成功 model={}", failedConfig.model);
            return answer;
        } catch (Exception e) {
            log.warn("[SelfHealing] 重试失败 model={} error={}", failedConfig.model, e.getMessage());
            errorAggregator.recordError(scene, failedConfig.provider, failedConfig.model,
                    ErrorType.fromException(e), e.getMessage());
            throw lastError;
        }
    }

    /**
     * 策略 d：降低 temperature 到 0.3 重试
     */
    private String retryWithLowerTemp(ModelConfig failedConfig, List<Map<String, Object>> messages,
                                      String scene,
                                      String defaultBaseUrl, String defaultApiKey,
                                      Exception lastError, double originalTemp) throws Exception {
        double newTemp = 0.3;
        log.info("[SelfHealing] PARSE_ERROR 降低 temperature {} -> {} 重试 model={}", originalTemp, newTemp, failedConfig.model);
        try {
            String answer = llmInvoker.invoke(failedConfig, messages, newTemp, scene, defaultBaseUrl, defaultApiKey);
            log.info("[SelfHealing] 重试成功 model={}", failedConfig.model);
            return answer;
        } catch (Exception e) {
            log.warn("[SelfHealing] 重试失败 model={} error={}", failedConfig.model, e.getMessage());
            errorAggregator.recordError(scene, failedConfig.provider, failedConfig.model,
                    ErrorType.fromException(e), e.getMessage());
            throw lastError;
        }
    }
}
