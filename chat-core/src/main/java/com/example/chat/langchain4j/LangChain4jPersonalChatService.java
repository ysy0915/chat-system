package com.example.chat.langchain4j;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.service.LLMInvoker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LangChain4j 版个人对话空间服务
 *
 * 已去除 langchain4j 框架依赖，改为：
 *   1. LLMInvoker 统一调用（走 chat-llm bundle 网关，失败自动回退直连）
 *   2. Redis 记忆（personal:{userId}，最近 20 条，TTL 7 天）
 *
 * 通过 app.langchain4j.enabled=true 开启
 */
@Service
@ConditionalOnProperty(name = "app.langchain4j.enabled", havingValue = "true")
public class LangChain4jPersonalChatService {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jPersonalChatService.class);

    /** 个人助手系统提示词（原 @SystemMessage） */
    private static final String SYSTEM_PROMPT = """
            你是用户的个人 AI 助手。你的角色：
            - 友好、专业，像一个全能的数字伙伴
            - 能回答各类问题：知识问答、生活建议、技术讨论等
            - 回答简洁清晰，必要时用列表/分点说明
            - 如果不确定，坦诚告知，不要编造
            - 支持中文和英文对话
            """;

    /** 记忆键前缀 */
    private static final String MEMORY_KEY_PREFIX = "personal:";
    /** 记忆保留条数（最近 20 条 = 10 轮） */
    private static final int MAX_MESSAGES = 20;
    /** 记忆 TTL（7 天） */
    private static final long MEMORY_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);

    @Autowired
    private LLMInvoker llmInvoker;

    @Autowired
    private ModelConfigRepository modelConfigRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LlmConfigProperties llmConfig;

    /**
     * 个人空间对话
     *
     * @param userId  用户 ID（记忆键 personal:{userId}）
     * @param message 用户消息
     * @return AI 回答
     */
    public String chat(Long userId, String message) {
        log.info("[LangChain4j-Personal] userId={} messageLen={}", userId, message.length());
        try {
            String redisKey = MEMORY_KEY_PREFIX + userId;
            List<LLMMessage> history = loadHistory(redisKey);

            List<LLMMessage> messages = new ArrayList<>();
            messages.add(LLMMessage.system(SYSTEM_PROMPT));
            messages.addAll(history);
            messages.add(LLMMessage.user(message));

            ModelConfig config = resolveModelConfig();
            String answer = llmInvoker.invoke(config, messages, 0.85, "personal",
                    llmConfig.getBaseUrl(), llmConfig.getApiKey());

            // 保存记忆：用户消息 + AI 回答
            history.add(LLMMessage.user(message));
            history.add(LLMMessage.assistant(answer));
            saveHistory(redisKey, history);

            log.info("[LangChain4j-Personal] 回答完成 userId={} answerLen={}", userId,
                    answer != null ? answer.length() : 0);
            return answer;
        } catch (LLMCallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LangChain4j-Personal] 调用失败 userId={} error={}", userId, e.getMessage());
            throw new LLMCallException("LangChain4j 调用失败", e);
        }
    }

    /**
     * 选择对话模型：优先 qwen，其次第一个启用的 chat 类型模型；无配置时回退 app.llm 默认
     */
    private ModelConfig resolveModelConfig() {
        List<ModelConfig> configs = modelConfigRepository.findAllEnabledByType("chat");
        if (!configs.isEmpty()) {
            for (ModelConfig c : configs) {
                if ("qwen".equals(c.provider)) {
                    return c;
                }
            }
            return configs.get(0);
        }
        ModelConfig fallback = new ModelConfig();
        fallback.provider = llmConfig.getProvider();
        fallback.model = llmConfig.getModel();
        fallback.apiKeyEncrypted = llmConfig.getApiKey();
        return fallback;
    }

    /** 从 Redis 加载历史记忆 */
    private List<LLMMessage> loadHistory(String redisKey) {
        try {
            String json = redisTemplate.opsForValue().get(redisKey);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            List<LLMMessage> history = new ArrayList<>();
            for (Map<String, Object> m : list) {
                Object content = m.get("content");
                if (content == null) {
                    continue;
                }
                String role = (String) m.get("role");
                if (role == null) {
                    continue;
                }
                history.add(new LLMMessage(role, content));
            }
            int from = Math.max(0, history.size() - MAX_MESSAGES);
            return new ArrayList<>(history.subList(from, history.size()));
        } catch (Exception e) {
            log.warn("[LangChain4j-Personal] 读取记忆失败 key={} error={}", redisKey, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 保存历史记忆到 Redis（保留最近 MAX_MESSAGES 条，TTL 7 天） */
    private void saveHistory(String redisKey, List<LLMMessage> history) {
        try {
            List<LLMMessage> trimmed = history.size() > MAX_MESSAGES
                    ? new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()))
                    : history;
            String json = objectMapper.writeValueAsString(
                    trimmed.stream().map(LLMMessage::toMap).toList());
            redisTemplate.opsForValue().set(redisKey, json, MEMORY_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[LangChain4j-Personal] 保存记忆失败 key={} error={}", redisKey, e.getMessage());
        }
    }
}
