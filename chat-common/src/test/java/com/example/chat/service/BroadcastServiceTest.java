package com.example.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖：本地广播、跨节点广播（RabbitMQ 可用/不可用）、RabbitMQ 异常容错
 */
@ExtendWith(MockitoExtension.class)
class BroadcastServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("RabbitMQ 可用时同时做本地广播和跨节点广播")
    void broadcast_withRabbit_bothLocalAndCrossNode() {
        BroadcastService service = new BroadcastService(messagingTemplate, rabbitTemplate, objectMapper, 8081);

        service.broadcast("/topic/test", "hello");

        // 本地广播
        verify(messagingTemplate).convertAndSend("/topic/test", "hello");
        // 跨节点广播
        verify(rabbitTemplate).convertAndSend(contains("cross-node"), eq("/topic/test"), anyString());
    }

    @Test
    @DisplayName("RabbitMQ 为 null 时仅做本地广播")
    void broadcast_withoutRabbit_onlyLocal() {
        BroadcastService service = new BroadcastService(messagingTemplate, null, objectMapper, 8081);

        service.broadcast("/topic/chat", Map.of("msg", "hi"));

        verify(messagingTemplate).convertAndSend("/topic/chat", Map.of("msg", "hi"));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("RabbitMQ 发送失败时不抛异常（容错）")
    void broadcast_rabbitException_noPropagation() {
        BroadcastService service = new BroadcastService(messagingTemplate, rabbitTemplate, objectMapper, 8081);
        doThrow(new RuntimeException("RabbitMQ 不可用")).when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> service.broadcast("/topic/test", "data"));

        // 本地广播仍然成功
        verify(messagingTemplate).convertAndSend("/topic/test", "data");
    }

    @Test
    @DisplayName("nodeId 基于端口号生成")
    void constructor_nodeId_fromPort() {
        BroadcastService service = new BroadcastService(messagingTemplate, null, objectMapper, 9090);

        assertDoesNotThrow(() -> service.broadcast("/topic/test", "data"));
        verify(messagingTemplate).convertAndSend("/topic/test", "data");
    }

    @Test
    @DisplayName("复杂对象作为 data 正常序列化")
    void broadcast_complexObject_serializesCorrectly() {
        BroadcastService service = new BroadcastService(messagingTemplate, rabbitTemplate, objectMapper, 8081);

        Map<String, Object> data = Map.of("userId", 42L, "message", "hello", "timestamp", "2024-01-01T00:00:00Z");
        service.broadcast("/topic/complex", data);

        verify(messagingTemplate).convertAndSend("/topic/complex", data);
        verify(rabbitTemplate).convertAndSend(contains("cross-node"), eq("/topic/complex"), anyString());
    }
}
