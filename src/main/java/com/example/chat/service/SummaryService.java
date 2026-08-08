package com.example.chat.service;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.ModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 对话摘要服务
 * - 用轻量模型为每条问答生成 15 字以内的主题摘要
 * - 异步执行，失败不阻塞主流程
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);

    private final LLMInvoker llmInvoker;
    private final ModelConfigRepository modelConfigRepository;
    private final MessageRepository messageRepository;

    @Value("${app.llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String defaultBaseUrl;

    @Value("${app.llm.api-key:}")
    private String defaultApiKey;

    @Value("${app.llm.model:qwen-plus}")
    private String defaultModel;

    @Value("${app.llm.provider:qwen}")
    private String defaultProvider;

    @Autowired
    public SummaryService(LLMInvoker llmInvoker,
                          ModelConfigRepository modelConfigRepository,
                          MessageRepository messageRepository) {
        this.llmInvoker = llmInvoker;
        this.modelConfigRepository = modelConfigRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 生成对话摘要（15 字以内）
     */
    public String generateSummary(String question, String answer) {
        if (question == null) question = "";
        if (answer == null) answer = "";

        // 截断超长内容，避免 token 浪费
        String q = question.length() > 500 ? question.substring(0, 500) : question;
        String a = answer.length() > 1000 ? answer.substring(0, 1000) : answer;

        String prompt = "请用15个字以内概括以下对话的主题：\n问：" + q + "\n答：" + a;

        ModelConfig config = pickLightweightConfig();

        try {
            String result = llmInvoker.invoke(config, prompt, 0.3, "summary",
                    defaultBaseUrl, defaultApiKey);
            if (result == null) return null;

            // 清理 LLM 输出：去引号、去换行、截断到 200 字符
            String summary = result.trim()
                    .replaceAll("^[\"“”'‘]+", "")
                    .replaceAll("[\"“”'‘]+$", "")
                    .replaceAll("[\\r\\n]+", " ")
                    .trim();
            if (summary.length() > 200) {
                summary = summary.substring(0, 200);
            }
            return summary;
        } catch (Exception ex) {
            log.warn("[Summary] 生成摘要失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 异步生成摘要并更新到 messages 表
     * 失败不阻塞主流程
     */
    @Async
    public void summarizeAsync(Long messageId, String question, String answer) {
        if (messageId == null) return;
        try {
            String summary = generateSummary(question, answer);
            if (summary == null || summary.isBlank()) return;

            int rows = messageRepository.updateSummary(messageId, summary);
            log.info("[Summary] messageId={} summary='{}' updated rows={}", messageId, summary, rows);
        } catch (Exception ex) {
            log.warn("[Summary] 异步更新摘要失败 messageId={}: {}", messageId, ex.getMessage());
        }
    }

    /**
     * 选择轻量模型配置：优先 provider=qwen，回退到任意 chat 模型，最后用默认配置
     */
    private ModelConfig pickLightweightConfig() {
        try {
            List<ModelConfig> enabled = modelConfigRepository.findAllEnabled();
            if (enabled != null && !enabled.isEmpty()) {
                // 优先 qwen
                List<ModelConfig> qwenConfigs = enabled.stream()
                        .filter(c -> "qwen".equalsIgnoreCase(c.provider)
                                && (c.modelType == null || "chat".equalsIgnoreCase(c.modelType)))
                        .sorted(Comparator.comparingInt(c -> c.priority != null ? c.priority : 100))
                        .toList();
                if (!qwenConfigs.isEmpty()) {
                    return qwenConfigs.get(0);
                }

                // 任意 chat 模型
                List<ModelConfig> chatConfigs = enabled.stream()
                        .filter(c -> c.modelType == null || "chat".equalsIgnoreCase(c.modelType))
                        .sorted(Comparator.comparingInt(c -> c.priority != null ? c.priority : 100))
                        .toList();
                if (!chatConfigs.isEmpty()) {
                    return chatConfigs.get(0);
                }
            }
        } catch (Exception ex) {
            log.warn("[Summary] 读取模型配置失败，使用默认配置: {}", ex.getMessage());
        }

        // 兜底：使用默认 qwen 配置
        ModelConfig fallback = new ModelConfig();
        fallback.provider = defaultProvider;
        fallback.model = defaultModel;
        fallback.apiKeyEncrypted = defaultApiKey;
        fallback.priority = 100;
        fallback.enabled = true;
        fallback.modelType = "chat";
        return fallback;
    }
}
