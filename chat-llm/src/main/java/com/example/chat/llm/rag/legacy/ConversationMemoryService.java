package com.example.chat.llm.rag.legacy;

import com.example.chat.llm.rag.legacy.LegacyEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 对话记忆服务
 *
 * 两层记忆架构：
 *   1. 短期记忆（Redis）：最近 N 轮对话，完整保留，快速读取
 *   2. 长期记忆（Milvus）：所有历史对话向量化，按相关性检索
 *
 * 使用方式（业务层调用）：
 *   // 保存一轮对话
 *   memoryService.saveConversation("treehole", userId, question, answer);
 *
 *   // 构建记忆上下文（拼入 prompt）
 *   String memory = memoryService.buildMemoryContext("treehole", userId, question);
 *   // memory 格式：
 *   //   【最近对话】
 *   //   用户: xxx
 *   //   AI: xxx
 *   //   【相关历史】
 *   //   用户(3天前): xxx
 *   //   AI: xxx
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    private StringRedisTemplate redisTemplate;
    private LegacyEmbeddingService embeddingService;
    private LegacyVectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;

    @Value("${app.rag.memory.short-term-rounds:5}")
    private int shortTermRounds;

    @Value("${app.rag.memory.long-term-top-k:3}")
    private int longTermTopK;

    @Value("${app.rag.memory.long-term-threshold:0.5}")
    private float longTermThreshold;

    @Value("${app.rag.memory.redis-ttl-hours:24}")
    private int redisTtlHours;

    /** Milvus 中对话记忆的 Collection 名 */
    private static final String MEMORY_COLLECTION = "conversation_memory";

    @Autowired
    public ConversationMemoryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void setRedisTemplate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Autowired(required = false)
    public void setEmbeddingService(LegacyEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @Autowired(required = false)
    public void setVectorStoreService(LegacyVectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    // ==================== 保存对话 ====================

    /**
     * 保存一轮对话到短期 + 长期记忆
     *
     * @param scene    场景（treehole / personal / chat）
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   AI 回答
     */
    public void saveConversation(String scene, Long userId, String question, String answer) {
        if (question == null || question.isBlank()) return;

        // 1. 存短期记忆（Redis）
        saveToShortTerm(scene, userId, question, answer);

        // 2. 存长期记忆（Milvus）
        saveToLongTerm(scene, userId, question, answer);
    }

    private void saveToShortTerm(String scene, Long userId, String question, String answer) {
        if (redisTemplate == null) return;
        try {
            String key = getShortTermKey(scene, userId);
            String entry = objectMapper.writeValueAsString(Map.of(
                    "question", question,
                    "answer", answer != null ? answer : "",
                    "timestamp", System.currentTimeMillis()
            ));
            redisTemplate.opsForList().rightPush(key, entry);
            // 只保留最近 N 轮（每轮 = 1 条 entry）
            redisTemplate.opsForList().trim(key, -shortTermRounds, -1);
            redisTemplate.expire(key, redisTtlHours, TimeUnit.HOURS);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | org.springframework.data.redis.RedisSystemException e) {
            log.warn("[Memory] 短期记忆保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    private void saveToLongTerm(String scene, Long userId, String question, String answer) {
        if (vectorStoreService == null || embeddingService == null) return;
        try {
            ensureMemoryCollection();

            // 把整轮对话（问+答）作为一个向量
            String combined = "用户: " + question + "\nAI: " + (answer != null ? answer : "");
            float[] vector = embeddingService.embed(combined);

            // 构建插入数据
            List<Float> vec = new ArrayList<>();
            for (float v : vector) vec.add(v);

            List<LegacyVectorStoreService.ChunkText> chunks = List.of(
                    new LegacyVectorStoreService.ChunkText(combined, 0)
            );
            // 用固定 kbId=-1 表示记忆库，source 记录场景和时间
            String source = scene + "|" + userId + "|" + System.currentTimeMillis();
            vectorStoreService.insertChunks(-1L, -1L, chunks, source);

            log.info("[Memory] 长期记忆保存成功 scene={} user={} qLen={} aLen={}",
                    scene, userId, question.length(), answer != null ? answer.length() : 0);
        } catch (Exception e) {
            log.warn("[Memory] 长期记忆保存失败 scene={} user={} error={}", scene, userId, e.getMessage());
        }
    }

    // ==================== 构建记忆上下文 ====================

    /**
     * 构建记忆上下文，拼入 prompt 的 system 消息中
     *
     * @param scene       场景
     * @param userId      用户 ID
     * @param currentQuestion 当前问题（用于检索相关长期记忆）
     * @return 记忆上下文字符串（无记忆时返回 null）
     */
    public String buildMemoryContext(String scene, Long userId, String currentQuestion) {
        // 1. 获取短期记忆（最近 N 轮）
        List<ConversationEntry> shortTerm = getShortTerm(scene, userId);

        // 2. 检索长期记忆（与当前问题相关的历史对话）
        List<ConversationEntry> longTerm = getLongTerm(scene, userId, currentQuestion);

        if (shortTerm.isEmpty() && longTerm.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是你的记忆信息，请参考但不要逐字重复：\n\n");

        if (!shortTerm.isEmpty()) {
            sb.append("【最近对话】\n");
            for (ConversationEntry e : shortTerm) {
                sb.append("用户: ").append(e.question).append('\n');
                sb.append("AI: ").append(e.answer).append("\n\n");
            }
        }

        if (!longTerm.isEmpty()) {
            sb.append("【相关历史记忆】\n");
            for (ConversationEntry e : longTerm) {
                sb.append("用户(").append(e.timeDesc).append("): ").append(e.question).append("\n");
                sb.append("AI: ").append(e.answer).append("\n\n");
            }
        }

        return sb.toString();
    }

    private List<ConversationEntry> getShortTerm(String scene, Long userId) {
        if (redisTemplate == null) return Collections.emptyList();
        try {
            String key = getShortTermKey(scene, userId);
            List<String> entries = redisTemplate.opsForList().range(key, 0, -1);
            if (entries == null || entries.isEmpty()) return Collections.emptyList();

            List<ConversationEntry> result = new ArrayList<>();
            for (String entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.readValue(entry, Map.class);
                result.add(new ConversationEntry(
                        (String) m.get("question"),
                        (String) m.get("answer"),
                        formatTimeAgo(System.currentTimeMillis() - ((Number) m.get("timestamp")).longValue())
                ));
            }
            return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | org.springframework.data.redis.RedisSystemException e) {
            log.warn("[Memory] 短期记忆读取失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<ConversationEntry> getLongTerm(String scene, Long userId, String query) {
        if (vectorStoreService == null || query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        try {
            ensureMemoryCollection();
            List<LegacyVectorStoreService.SearchResult> results =
                    vectorStoreService.search(-1L, query, longTermTopK);

            List<ConversationEntry> result = new ArrayList<>();
            for (LegacyVectorStoreService.SearchResult r : results) {
                if (r.score < longTermThreshold) continue;

                // 解析对话内容（格式: "用户: xxx\nAI: xxx"）
                String[] parts = r.text.split("\nAI: ", 2);
                String question = parts[0].replaceFirst("^用户: ", "");
                String answer = parts.length > 1 ? parts[1] : "";

                // 解析时间和场景
                String timeDesc = "之前";
                if (r.source != null && r.source.contains("|")) {
                    String[] meta = r.source.split("\\|");
                    if (meta.length >= 3) {
                        try {
                            long ts = Long.parseLong(meta[2]);
                            timeDesc = formatTimeAgo(System.currentTimeMillis() - ts);
                        } catch (NumberFormatException ignored) {}
                    }
                }

                result.add(new ConversationEntry(question, answer, timeDesc));
            }
            return result;
        } catch (Exception e) {
            log.warn("[Memory] 长期记忆检索失败 scene={} user={} error={}", scene, userId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 工具方法 ====================

    private String getShortTermKey(String scene, Long userId) {
        return "memory:" + scene + ":" + userId;
    }

    private void ensureMemoryCollection() {
        if (vectorStoreService == null) return;
        // 用 kbId=-1 作为记忆库的 Collection
        vectorStoreService.ensureCollection(-1L);
    }

    private String formatTimeAgo(long millis) {
        long minutes = millis / 60000;
        if (minutes < 1) return "刚刚";
        if (minutes < 60) return minutes + "分钟前";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时前";
        long days = hours / 24;
        if (days < 30) return days + "天前";
        return days / 30 + "个月前";
    }

    // ==================== 清除记忆 ====================

    /**
     * 清除用户的短期记忆（Redis）
     */
    public void clearShortTerm(String scene, Long userId) {
        if (redisTemplate == null) return;
        redisTemplate.delete(getShortTermKey(scene, userId));
    }

    // ==================== 数据结构 ====================

    private static class ConversationEntry {
        final String question;
        final String answer;
        final String timeDesc;

        ConversationEntry(String question, String answer, String timeDesc) {
            this.question = question;
            this.answer = answer;
            this.timeDesc = timeDesc;
        }
    }
}
