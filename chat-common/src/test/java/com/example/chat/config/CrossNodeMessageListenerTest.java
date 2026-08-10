package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖：同节点消息忽略、跨节点消息转发、非法 JSON 容错、双编码 JSON 解析
 */
@ExtendWith(MockitoExtension.class)
class CrossNodeMessageListenerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private ObjectMapper objectMapper;
    private CrossNodeMessageListener listener;

    private static final String NODE_ID = "node-8081";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new CrossNodeMessageListener(messagingTemplate, objectMapper, NODE_ID);
    }

    @Test
    @DisplayName("同节点消息忽略（不转发）")
    void onMessage_sameNode_skips() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "_nodeId", NODE_ID,
                "destination", "/topic/test",
                "data", "hello"
        ));
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("不同节点消息转发到目标 destination")
    void onMessage_differentNode_forwards() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "_nodeId", "node-8083",
                "destination", "/topic/games",
                "data", Map.of("score", 100)
        ));
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message);

        ArgumentCaptor<String> destCaptor = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), any(Object.class));
        assertEquals("/topic/games", destCaptor.getValue());
    }

    @Test
    @DisplayName("非法 JSON 不抛异常（容错）")
    void onMessage_invalidJson_noException() {
        Message message = new Message("not-valid-json".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> listener.onMessage(message));
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("空消息体不抛异常")
    void onMessage_emptyBody_noException() {
        Message message = new Message(new byte[0]);

        assertDoesNotThrow(() -> listener.onMessage(message));
    }

    @Test
    @DisplayName("双编码 JSON 正确解析")
    void onMessage_doubleEncoded_parsesCorrectly() throws Exception {
        String innerJson = objectMapper.writeValueAsString(Map.of(
                "_nodeId", "node-8082",
                "destination", "/topic/chat",
                "data", "test"
        ));
        String doubleEncoded = objectMapper.writeValueAsString(innerJson);
        Message message = new Message(doubleEncoded.getBytes(StandardCharsets.UTF_8));

        listener.onMessage(message);

        verify(messagingTemplate).convertAndSend(eq("/topic/chat"), any(Object.class));
    }
}
