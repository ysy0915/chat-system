package com.example.chat.service;

import com.example.chat.dto.WsMessage;
import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import org.mockito.ArgumentCaptor;

/**
 * ChatCacheManager 单元测试
 */
@DisplayName("ChatCacheManager 对话缓存管理器")
class ChatCacheManagerTest {

    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;
    private MessageRepository messageRepository;
    private BroadcastService broadcastService;
    private ChatCacheManager cacheManager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        messageRepository = mock(MessageRepository.class);
        broadcastService = mock(BroadcastService.class);
        cacheManager = new ChatCacheManager(redisTemplate, new ObjectMapper(),
                broadcastService, messageRepository);
    }

    @Test
    @DisplayName("hitAndServe 缓存命中返回 true 并广播")
    void hitAndServe_cacheHit_returnsTrue() {
        when(valueOps.get(anyString())).thenReturn("缓存答案");
        Message msg = new Message();
        msg.reqId = "req-001";
        when(messageRepository.findByReqId("req-001")).thenReturn(msg);
        boolean result = cacheManager.hitAndServe("req-001", 1L, "什么是AI");
        assertTrue(result);
        verify(broadcastService).broadcast(eq("/topic/user.1"), any(Map.class));
        verify(messageRepository).updateByReqId(msg);
        assertEquals("done", msg.status);
    }

    @Test
    @DisplayName("hitAndServe 缓存未命中返回 false")
    void hitAndServe_cacheMiss_returnsFalse() {
        when(valueOps.get(anyString())).thenReturn(null);
        boolean result = cacheManager.hitAndServe("req-001", 1L, "什么是AI");
        assertFalse(result);
        verify(broadcastService, never()).broadcast(anyString(), any());
    }

    @Test
    @DisplayName("hitAndServe Redis 异常返回 false（fail-open）")
    void hitAndServe_redisException_returnsFalse() {
        when(valueOps.get(anyString())).thenThrow(new DataAccessException("conn refused") {});
        boolean result = cacheManager.hitAndServe("req-001", 1L, "什么是AI");
        assertFalse(result);
    }

    @Test
    @DisplayName("save 双写问题级 + 模型级缓存（TTL 24h）")
    void save_writesCache() {
        cacheManager.save("什么是AI", "doubao", "doubao-pro", "AI是人工智能");
        verify(valueOps, times(2)).set(anyString(), eq("AI是人工智能"), any(java.time.Duration.class));
    }

    @Test
    @DisplayName("save 写入两个不同 key（问题级与模型级）")
    void save_writesTwoDistinctKeys() {
        cacheManager.save("什么是AI", "doubao", "doubao-pro", "AI是人工智能");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps, times(2)).set(keyCaptor.capture(), eq("AI是人工智能"), any(java.time.Duration.class));
        assertEquals(2, keyCaptor.getAllValues().stream().distinct().count(),
                "问题级与模型级 key 必须不同，否则双写退化为单 key");
    }

    @Test
    @DisplayName("save Redis 异常不抛出（fail-open）")
    void save_redisException_noThrow() {
        doThrow(new DataAccessException("conn refused") {})
                .when(valueOps).set(anyString(), anyString(), any(java.time.Duration.class));
        assertDoesNotThrow(() -> cacheManager.save("问题", "p", "m", "答案"));
    }

    @Test
    @DisplayName("hitAndServe 缓存命中但消息不存在于 DB 仍返回 true")
    void hitAndServe_msgNotFound_stillReturnsTrue() {
        when(valueOps.get(anyString())).thenReturn("答案");
        when(messageRepository.findByReqId(anyString())).thenReturn(null);
        boolean result = cacheManager.hitAndServe("req-002", 1L, "问题");
        assertTrue(result);
        verify(broadcastService).broadcast(eq("/topic/user.1"), any(Map.class));
        verify(messageRepository, never()).updateByReqId(any());
    }
}
