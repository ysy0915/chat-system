package com.example.chat.service;

import com.example.chat.dto.WsMessage;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;

/**
 * 对话答案缓存管理器。
 *
 * <p>从 ChatProcessor 拆分：隔离缓存 key 构建、命中广播回填、写入 TTL 逻辑。</p>
 */
@Component
public class ChatCacheManager {

    private static final Logger log = LoggerFactory.getLogger(ChatCacheManager.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final BroadcastService broadcastService;
    private final MessageRepository messageRepository;

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    public ChatCacheManager(RedisTemplate<String, String> redisTemplate,
                            ObjectMapper objectMapper,
                            BroadcastService broadcastService,
                            MessageRepository messageRepository) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.broadcastService = broadcastService;
        this.messageRepository = messageRepository;
    }

    /**
     * 命中问题级缓存：直接广播答案并落库，返回 true；未命中返回 false。
     */
    public boolean hitAndServe(String reqId, Long userId, String question) {
        String cached = null;
        try {
            cached = redisTemplate.opsForValue().get(buildCacheKey(question));
        } catch (DataAccessException ex) {
            log.warn("Redis read failed, skipping cache: {}", ex.getMessage());
        }
        if (cached == null) return false;

        broadcastService.broadcast("/topic/user." + userId,
                WsMessage.of(WsMessage.TYPE_DONE).withReqId(reqId).with("answer", cached).toMap());
        Message m = messageRepository.findByReqId(reqId);
        if (m != null) {
            try {
                m.answerJson = objectMapper.writeValueAsString(Map.of("answer", cached));
            } catch (JsonProcessingException e) {
                m.answerJson = "{\"answer\":\"\"}";
            }
            m.status = "done";
            messageRepository.updateByReqId(m);
        }
        return true;
    }

    /** 写入问题+模型级缓存 */
    public void save(String question, String provider, String model, String answer) {
        String cacheKey = buildCacheKey(question, provider, model);
        try {
            redisTemplate.opsForValue().set(cacheKey, answer, CACHE_TTL);
        } catch (DataAccessException ex) {
            log.warn("[WARN] Redis write failed: {}", ex.getMessage());
        }
    }

    /**
     * 构建问题级缓存 key（不区分 provider/model，命中即所有模型共用）。
     */
    private String buildCacheKey(String question) {
        return "question:" + sha256(question + "::model-pool");
    }

    /**
     * 构建问题+模型级缓存 key（区分 provider/model）。
     */
    private String buildCacheKey(String question, String provider, String model) {
        return "question:" + sha256(question + "::" + (provider == null ? "" : provider) + "::" + (model == null ? "" : model));
    }

    /** 计算输入字符串的 SHA-256 哈希值（16 进制字符串）；计算失败时回退到 hashCode */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
