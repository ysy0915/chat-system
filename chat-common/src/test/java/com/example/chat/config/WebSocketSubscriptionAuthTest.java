package com.example.chat.config;

import com.example.chat.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.simp.config.ChannelRegistration;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

/**
 * WebSocket 订阅级鉴权断言（越权防护核心）：
 * 私有 topic（/topic/xxx.{userId}）仅允许 token 归属者本人订阅；
 * 匿名连接与他人 topic 一律拒绝；公开 topic 不受影响。
 */
@ExtendWith(MockitoExtension.class)
class WebSocketSubscriptionAuthTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private ChannelRegistration registration;

    private ChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        WebSocketConfig config = new WebSocketConfig(jwtUtil);
        config.configureClientInboundChannel(registration);
        ArgumentCaptor<ChannelInterceptor> captor = ArgumentCaptor.forClass(ChannelInterceptor.class);
        verify(registration).interceptors(captor.capture());
        interceptor = captor.getValue();
        assertNotNull(interceptor);
    }

    /** 构造一条 SUBSCRIBE 帧 */
    private Message<?> subscribeFrame(String destination, Long userId, Boolean authed) {
        StompHeaderAccessor h = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        h.setDestination(destination);
        if (userId != null) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("userId", String.valueOf(userId));
            attrs.put("authed", authed);
            h.setSessionAttributes(attrs);
        }
        h.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], h.getMessageHeaders());
    }

    @Test
    @DisplayName("已登录用户订阅自己的私有 topic → 放行")
    void authedUser_ownPrivateTopic_allowed() {
        Message<?> msg = subscribeFrame("/topic/user.7", 7L, true);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("已登录用户订阅他人的私有 topic → 拒绝（越权防护）")
    void authedUser_othersPrivateTopic_denied() {
        Message<?> msg = subscribeFrame("/topic/user.999", 7L, true);
        assertThrows(MessagingException.class, () -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("匿名连接订阅敏感私有 topic（user/treehole）→ 拒绝")
    void anonymous_sensitivePrivateTopic_denied() {
        assertThrows(MessagingException.class,
                () -> interceptor.preSend(subscribeFrame("/topic/user.7", null, null), null));
        assertThrows(MessagingException.class,
                () -> interceptor.preSend(subscribeFrame("/topic/treehole.7", null, null), null));
    }

    @Test
    @DisplayName("匿名连接订阅游客功能 topic（debate）→ 放行（辩论场支持匿名发起，须能收到自己的事件流）")
    void anonymous_guestDebateTopic_allowed() {
        Message<?> msg = subscribeFrame("/topic/debate.7", null, null);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("匿名连接订阅公开 topic → 放行")
    void anonymous_publicTopic_allowed() {
        Message<?> msg = subscribeFrame("/topic/online-count/all", null, null);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("广播类公开 topic（无数字后缀）→ 放行")
    void publicTopicWithSlash_allowed() {
        Message<?> msg = subscribeFrame("/topic/online-count/personal", 7L, true);
        assertDoesNotThrow(() -> interceptor.preSend(msg, null));
    }

    @Test
    @DisplayName("会话属性缺失时 fail-close：拒绝私有 topic")
    void missingSessionAttributes_failClosed() {
        Message<?> msg = subscribeFrame("/topic/treehole.7", null, null);
        assertThrows(MessagingException.class, () -> interceptor.preSend(msg, null));
    }
}
