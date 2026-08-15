package com.example.chat.config;

import com.example.chat.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Map;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final JwtUtil jwtUtil;

    public WebSocketConfig(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(1);
        taskScheduler.setThreadNamePrefix("ws-heartbeat-");
        taskScheduler.initialize();

        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{25000, 25000})
                .setTaskScheduler(taskScheduler);

        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(128 * 1024)
                .setSendTimeLimit(15000)
                .setSendBufferSizeLimit(512 * 1024);
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxSessionIdleTimeout(900000L); // 15 分钟无操作断开
        container.setMaxTextMessageBufferSize(128 * 1024);
        container.setMaxBinaryMessageBufferSize(128 * 1024);
        return container;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/chat")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        if (!(request instanceof ServletServerHttpRequest)) {
                            return false;
                        }
                        ServletServerHttpRequest servletRequest = (ServletServerHttpRequest) request;
                        // SockJS 无法自定义 HTTP Header，JWT 经 query 参数传递（token）
                        String token = servletRequest.getServletRequest().getParameter("token");
                        if (token != null && !token.isBlank()) {
                            if (!jwtUtil.validateToken(token)) {
                                log.warn("[WS] 握手拒绝：JWT 无效");
                                return false;
                            }
                            // 以 token 中的 uid 为准，忽略（可能被伪造的）userId query 参数
                            Long uid = jwtUtil.getUserId(token);
                            if (uid == null) {
                                log.warn("[WS] 握手拒绝：JWT 中无 uid claim");
                                return false;
                            }
                            attributes.put("userId", String.valueOf(uid));
                            attributes.put("authed", Boolean.TRUE);
                        } else {
                            // 匿名连接：仅允许订阅公开 topic（如在线人数），私有 topic 在订阅层拒绝
                            attributes.put("authed", Boolean.FALSE);
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {}
                })
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
                    return message;
                }
                String destination = accessor.getDestination();
                if (destination == null) {
                    return message;
                }
                // 仅对用户私有 topic 做订阅鉴权
                if (!isPrivateTopic(destination)) {
                    return message;
                }
                // 从握手 attributes 读取绑定的 userId（仅登录连接有）
                Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
                String boundUserId = sessionAttrs == null ? null : (String) sessionAttrs.get("userId");
                Boolean authed = sessionAttrs == null ? null : (Boolean) sessionAttrs.get("authed");
                if (boundUserId == null || !Boolean.TRUE.equals(authed)) {
                    log.warn("[WS] 订阅拒绝：匿名连接尝试订阅私有 topic {}", destination);
                    throw new org.springframework.messaging.MessagingException("未登录，无法订阅私有通道");
                }
                // 校验 topic 中的 userId 与 token 绑定的 userId 一致，防止横向越权订阅他人消息
                String topicUserId = extractTopicUserId(destination);
                if (topicUserId != null && !topicUserId.equals(boundUserId)) {
                    log.warn("[WS] 订阅拒绝：越权订阅 {}（token uid={}）", destination, boundUserId);
                    throw new org.springframework.messaging.MessagingException("无权订阅他人通道");
                }
                return message;
            }
        });
    }

    /** 用户私有 topic 前缀（含用户 ID 的推送通道，需鉴权） */
    private static boolean isPrivateTopic(String destination) {
        return destination.startsWith("/topic/user.")
                || destination.startsWith("/topic/debate.")
                || destination.startsWith("/topic/treehole.");
    }

    /** 从 /topic/user.123 提取 "123" */
    private static String extractTopicUserId(String destination) {
        int lastDot = destination.lastIndexOf('.');
        if (lastDot < 0 || lastDot == destination.length() - 1) {
            return null;
        }
        return destination.substring(lastDot + 1);
    }
}
