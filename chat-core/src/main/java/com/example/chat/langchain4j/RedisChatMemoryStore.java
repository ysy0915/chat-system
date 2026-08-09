package com.example.chat.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的 ChatMemoryStore
 * <p>
 * 将对话记忆持久化到 Redis，重启后不丢失。
 * 存储格式：JSON序列化的消息列表
 * Key格式：langchain4j:memory:{memoryId}
 * TTL：7天自动过期
 */
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);
    private static final String KEY_PREFIX = "langchain4j:memory:";
    private static final long TTL_DAYS = 7;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + memoryId);
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json, new TypeReference<List<ChatMessage>>() {});
        } catch (Exception e) {
            log.warn("[RedisChatMemoryStore] 读取记忆失败 id={}: {}", memoryId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        try {
            String json = objectMapper.writeValueAsString(messages);
            redisTemplate.opsForValue().set(KEY_PREFIX + memoryId, json, TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[RedisChatMemoryStore] 保存记忆失败 id={}: {}", memoryId, e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        try {
            redisTemplate.delete(KEY_PREFIX + memoryId);
        } catch (Exception e) {
            log.warn("[RedisChatMemoryStore] 删除记忆失败 id={}: {}", memoryId, e.getMessage());
        }
    }
}
