package com.example.chat.service;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 历史对话摘要服务
 *
 * 策略：当历史消息估算 token 超过阈值时，保留最近 N 轮原文，
 * 将更早的消息用 LLM 压缩为一段摘要，注入到 system prompt 中。
 * 摘要缓存在 Redis，24 小时有效，避免每次对话重复调用 LLM。
 */
@Service
public class HistorySummaryService {

    private static final Logger log = LoggerFactory.getLogger(HistorySummaryService.class);

    private static final int MAX_RECENT_ROUNDS = 6;
    private static final int TOKEN_THRESHOLD = 4000;
    private static final double CHARS_PER_TOKEN = 3.5;
    private static final int MAX_SUMMARY_CHARS = 300;
    private static final int SUMMARY_CACHE_HOURS = 24;
    private static final String SUMMARY_KEY_PREFIX = "history_summary:";

    @Autowired(required = false)
    private LLMInvoker llmInvoker;

    @Autowired(required = false)
    private ModelConfigRepository modelConfigRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final LlmConfigProperties llmConfig;

    @Autowired
    public HistorySummaryService(LlmConfigProperties llmConfig) {
        this.llmConfig = llmConfig;
    }

    /**
     * 压缩历史消息：保留最近轮次原文，更早的生成摘要。
     *
     * @param scene           场景标识（personal / treehole / file）
     * @param userId          用户ID
     * @param historyMessages 完整历史消息列表（不含 system prompt）
     * @param systemPrompt    系统提示词构建器（摘要将追加到此）
     * @return 压缩后的消息列表
     */
    public List<LLMMessage> compress(String scene, Long userId,
                                      List<LLMMessage> historyMessages,
                                      StringBuilder systemPrompt) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return historyMessages;
        }

        int estimatedTokens = estimateTokens(historyMessages);
        int recentMsgCount = MAX_RECENT_ROUNDS * 2;

        if (estimatedTokens <= TOKEN_THRESHOLD || historyMessages.size() <= recentMsgCount) {
            return historyMessages;
        }

        // 拆分为「更早的消息」和「最近的消息」
        int splitIdx = historyMessages.size() - recentMsgCount;
        List<LLMMessage> oldMessages = new ArrayList<>(historyMessages.subList(0, splitIdx));
        List<LLMMessage> recentMessages = new ArrayList<>(historyMessages.subList(splitIdx, historyMessages.size()));

        // 尝试从缓存获取摘要
        String cacheKey = SUMMARY_KEY_PREFIX + scene + ":" + userId;
        String cachedSummary = stringRedisTemplate.opsForValue().get(cacheKey);

        if (cachedSummary != null && !cachedSummary.isBlank()) {
            log.info("[HistorySummary] 使用缓存摘要 scene={} userId={} 长度={}", scene, userId, cachedSummary.length());
            systemPrompt.append("\n\n【历史对话摘要】").append(cachedSummary);
            return recentMessages;
        }

        log.info("[HistorySummary] 开始生成摘要 scene={} userId={} 旧消息数={} 估算tokens={}",
                scene, userId, oldMessages.size(), estimatedTokens);

        String summary = generateSummary(oldMessages);
        if (summary != null && !summary.isBlank()) {
            stringRedisTemplate.opsForValue().set(cacheKey, summary, SUMMARY_CACHE_HOURS, TimeUnit.HOURS);
            systemPrompt.append("\n\n【历史对话摘要】").append(summary);
            log.info("[HistorySummary] 摘要已生成并缓存 scene={} userId={} 摘要长度={}", scene, userId, summary.length());
        } else {
            log.warn("[HistorySummary] 摘要生成失败，保留所有历史 scene={} userId={}", scene, userId);
            return historyMessages;
        }

        return recentMessages;
    }

    public void invalidateCache(String scene, Long userId) {
        String cacheKey = SUMMARY_KEY_PREFIX + scene + ":" + userId;
        stringRedisTemplate.delete(cacheKey);
        log.debug("[HistorySummary] 已清除缓存 scene={} userId={}", scene, userId);
    }

    // ==================== 内部方法 ====================

    private String generateSummary(List<LLMMessage> oldMessages) {
        if (llmInvoker == null) {
            log.warn("[HistorySummary] LLMInvoker 不可用，跳过摘要生成");
            return null;
        }

        StringBuilder conversation = new StringBuilder();
        for (LLMMessage msg : oldMessages) {
            String role = msg.getRole();
            String text = msg.getTextContent();
            if (text != null) {
                if (text.length() > 2000) text = text.substring(0, 2000) + "...";
                conversation.append(role).append(": ").append(text).append("\n");
            }
        }

        String conversationText = conversation.toString();
        if (conversationText.length() > 6000) {
            conversationText = conversationText.substring(0, 6000) + "\n...[内容过长已截断]";
        }

        List<LLMMessage> summaryMessages = List.of(
                LLMMessage.system(
                        "你是一个对话摘要助手。请用不超过200字总结以下对话中的关键信息，" +
                        "包括：用户讨论的话题、表达的需求、重要的事实或偏好。" +
                        "只提取信息，不要评价或追问。如果信息不足以总结，回复「无」即可。"),
                LLMMessage.user("请简要总结以下对话：\n\n" + conversationText)
        );

        ModelConfig summaryConfig = findSummaryModelConfig();
        if (summaryConfig == null) {
            log.warn("[HistorySummary] 未找到可用的摘要模型配置");
            return null;
        }

        try {
            String summary = llmInvoker.invoke(
                    summaryConfig, summaryMessages, 0.3, "summary",
                    llmConfig.getBaseUrl(), llmConfig.getApiKey()
            );
            if (summary != null) {
                summary = summary.trim();
                if (summary.length() > MAX_SUMMARY_CHARS) {
                    summary = summary.substring(0, MAX_SUMMARY_CHARS) + "...";
                }
            }
            return summary;
        } catch (Exception e) {
            log.warn("[HistorySummary] 摘要生成调用失败: {}", e.getMessage());
            return null;
        }
    }

    private int estimateTokens(List<LLMMessage> messages) {
        int total = 0;
        for (LLMMessage msg : messages) {
            String text = msg.getTextContent();
            if (text != null) {
                total += (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
            } else if (msg.getContent() instanceof List<?> parts) {
                for (Object part : parts) {
                    if (part instanceof com.example.chat.dto.ContentPart cp && cp.getText() != null) {
                        total += (int) Math.ceil(cp.getText().length() / CHARS_PER_TOKEN);
                    }
                }
            }
        }
        return total;
    }

    private ModelConfig findSummaryModelConfig() {
        if (modelConfigRepository == null) return null;
        try {
            List<ModelConfig> all = modelConfigRepository.findAllEnabled();
            if (all == null || all.isEmpty()) return null;
            for (ModelConfig c : all) {
                if ("summary".equals(c.modelType)) return c;
            }
            for (ModelConfig c : all) {
                if ("qwen-turbo".equals(c.model)) return c;
            }
            return all.stream()
                    .sorted(Comparator.comparingInt(c -> c.priority != null ? c.priority : 100))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[HistorySummary] 查找摘要模型失败: {}", e.getMessage());
            return null;
        }
    }
}
