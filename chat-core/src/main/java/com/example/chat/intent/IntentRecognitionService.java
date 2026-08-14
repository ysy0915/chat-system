package com.example.chat.intent;

import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 基于 LLM 的意图识别服务
 * <p>
 * 调用轻量模型（如 qwen-turbo）分类用户输入，输出 JSON 结构化意图。
 * 特性：本地短期缓存、超时兜底、低置信度降级为 UNKNOWN。
 */
@Service
@ConditionalOnProperty(name = "app.intent.enabled", havingValue = "true", matchIfMissing = true)
public class IntentRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本地短期缓存（LRU 近似：最多 500 条，10 秒后作废） */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Autowired
    private LLMInvoker llmInvoker;

    @Autowired
    private LlmConfigProperties llmConfig;

    /** 意图识别使用的轻量模型（比默认 qwen-plus 更快更便宜） */
    @Value("${app.intent.model:qwen-turbo}")
    private String intentModel;

    /** 最小置信度阈值（低于此值降级为 UNKNOWN） */
    @Value("${app.intent.min-confidence:0.5}")
    private double minConfidence;

    /** LLM 调用超时（秒） */
    @Value("${app.intent.timeout-seconds:3}")
    private int timeoutSeconds;

    /** 意图识别 LLM 温度（低温度保证分类稳定） */
    @Value("${app.intent.temperature:0.1}")
    private double temperature;

    /** 分类 System Prompt */
    private static final String SYSTEM_PROMPT = """
你是一个意图分类器。根据用户的输入，判断其意图类别。

## 意图类别（必须从以下10类中选择其一）：
1. GENERAL_CHAT      - 日常闲聊、问候、寒暄、无明确主题
2. KNOWLEDGE_QA      - 知识问答、事实查询、概念解释、教程请求
3. CODE_GENERATION   - 编程开发、写代码、调试、技术方案
4. CREATIVE_WRITING  - 创作故事、诗歌、文案、剧本等
5. REASONING         - 逻辑推理、数学计算、复杂分析
6. SUMMARIZATION     - 总结概括、提炼要点
7. EMOTIONAL_SUPPORT - 情绪倾诉、心理支持、情感交流
8. TASK_EXECUTION    - 要求执行具体任务（搜索、计算、格式转换等）
9. TRANSLATION       - 翻译
10. UNKNOWN          - 以上都不匹配或难以判断

## 输出格式：
严格输出 JSON，不要输出任何其他内容：
{"category":"GENERAL_CHAT","confidence":0.85,"reasoning":"简单的问候语，无明确任务","entities":""}

## 规则：
- confidence 为 0.0~1.0 的置信度
- entities 提取关键名词/概念，用逗号分隔（无则留空）
- 短输入（≤5字）默认 GENERAL_CHAT，除非明显是命令/问题
""";

    /**
     * 识别用户输入意图（同步，带超时）
     *
     * @param userInput 用户原始输入
     * @param scene 业务场景（chat/debate/treehole/personal）
     * @return 意图识别结果（超时/失败时返回 UNKNOWN）
     */
    public IntentResult recognize(String userInput, String scene) {
        if (userInput == null || userInput.isBlank()) {
            return IntentResult.unknown();
        }

        // 1. 查本地缓存
        String cacheKey = scene + ":" + userInput.trim();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.debug("[Intent] cache hit scene={} category={}", scene, cached.result.category());
            return cached.result;
        }

        // 2. LLM 分类（带超时）
        try {
            IntentResult result = CompletableFuture
                    .supplyAsync(() -> classify(userInput))
                    .get(timeoutSeconds, TimeUnit.SECONDS);

            // 3. 写入缓存
            cache.put(cacheKey, new CacheEntry(result));
            evictIfNeeded();

            log.info("[Intent] userInput=\"{}\" scene={} category={} confidence={}",
                    userInput.length() > 50 ? userInput.substring(0, 50) + "..." : userInput,
                    scene, result.category(), result.confidence());
            return result;

        } catch (Exception e) {
            log.warn("[Intent] 分类超时或异常 scene={} error={}", scene, e.getMessage());
            IntentResult fallback = IntentResult.unknown();
            cache.put(cacheKey, new CacheEntry(fallback));
            return fallback;
        }
    }

    /**
     * 调用 LLM 进行意图分类
     */
    private IntentResult classify(String userInput) {
        ModelConfig config = new ModelConfig();
        config.provider = llmConfig.getProvider();
        config.model = intentModel;

        List<LLMMessage> messages = List.of(
                LLMMessage.system(SYSTEM_PROMPT),
                LLMMessage.user(userInput)
        );

        try {
            String rawJson = llmInvoker.invoke(config, messages, temperature,
                    "intent", llmConfig.getBaseUrl(), llmConfig.getApiKey());

            // 清理 LLM 可能包裹的 ```json ... ```
            String json = extractJson(rawJson);
            JsonNode root = MAPPER.readTree(json);

            String categoryStr = root.has("category") ? root.get("category").asText() : "UNKNOWN";
            double confidence = root.has("confidence") ? root.get("confidence").asDouble() : 0.0;
            String reasoning = root.has("reasoning") ? root.get("reasoning").asText() : "";
            String entities = root.has("entities") ? root.get("entities").asText() : "";

            IntentCategory category = parseCategory(categoryStr);

            // 低置信度降级
            if (confidence < minConfidence) {
                log.debug("[Intent] 低置信度 category={} confidence={}<{} 降级为 UNKNOWN",
                        category, confidence, minConfidence);
                category = IntentCategory.UNKNOWN;
            }

            return new IntentResult(category, Math.min(1.0, Math.max(0.0, confidence)),
                    reasoning, entities);

        } catch (Exception e) {
            log.warn("[Intent] 分类 JSON 解析失败: {}", e.getMessage());
            return IntentResult.unknown();
        }
    }

    /** 从 LLM 原始输出中提取 JSON */
    String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        // 去 ```json ... ```
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    /** 解析意图类别字符串 */
    @SuppressWarnings("PMD.NPathComplexity") // 连续模糊匹配关键字映射，拆分为 Map 反而损失可读性
    IntentCategory parseCategory(String name) {
        try {
            return IntentCategory.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // 模糊匹配
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.contains("CHAT")) return IntentCategory.GENERAL_CHAT;
            if (upper.contains("KNOWLEDGE") || upper.contains("QA")) return IntentCategory.KNOWLEDGE_QA;
            if (upper.contains("CODE")) return IntentCategory.CODE_GENERATION;
            if (upper.contains("CREATIVE") || upper.contains("WRITE")) return IntentCategory.CREATIVE_WRITING;
            if (upper.contains("REASON") || upper.contains("LOGIC")) return IntentCategory.REASONING;
            if (upper.contains("SUMMARY")) return IntentCategory.SUMMARIZATION;
            if (upper.contains("EMOTION") || upper.contains("SUPPORT")) return IntentCategory.EMOTIONAL_SUPPORT;
            if (upper.contains("TASK") || upper.contains("EXEC")) return IntentCategory.TASK_EXECUTION;
            if (upper.contains("TRANSLAT")) return IntentCategory.TRANSLATION;
            return IntentCategory.UNKNOWN;
        }
    }

    /** 缓存淘汰：超过 500 条时清空 */
    private void evictIfNeeded() {
        if (cache.size() > 500) {
            log.debug("[Intent] 缓存超过 500 条，清空");
            cache.clear();
        }
    }

    /** 本地缓存条目 */
    private record CacheEntry(IntentResult result, long timestamp) {
        CacheEntry(IntentResult result) {
            this(result, System.currentTimeMillis());
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > 10_000; // 10 秒过期
        }
    }
}
