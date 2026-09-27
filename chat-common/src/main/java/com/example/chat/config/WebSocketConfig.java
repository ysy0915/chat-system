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
import org.springframework.messaging.MessagingException;
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
import java.util.regex.Pattern;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    /**
     * 私有 topic 特征：/topic/<名称>.<数字userId>，如 /topic/user.1、/topic/debate.42、/topic/treehole.9。
     * 公开 topic（online-count、public-questions、castlesiege.state 等）不含 ".数字" 后缀，不受影响。
     */
    private static final Pattern PRIVATE_TOPIC = Pattern.compile("^/topic/[a-z]+\\.\\d+$");

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
        // 订阅级鉴权：私有 topic（/topic/xxx.{userId}）仅允许 token 归属者本人订阅。
        // 此前该层为空实现，任意已建立 WebSocket 连接的客户端（包括匿名连接）都能
        // 订阅他人的 /topic/user.{id}、/topic/debate.{id}、/topic/treehole.{id}，
        // 越权接收他人私聊与树洞的流式回答。
        //
        // 会话属性来自握手拦截器写入的 attributes（userId/authed），经 Spring 的
        // SESSION_ATTRIBUTES 头随 STOMP 帧透传，SUBSCRIBE 帧上稳定可取
        // （与 Spring Security WebSocket 的拦截器同一机制）。
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    return message;
                }
                String destination = accessor.getDestination();
                if (destination == null || !PRIVATE_TOPIC.matcher(destination).matches()) {
                    return message;
                }
                Map<String, Object> attrs = accessor.getSessionAttributes();
                String userId = attrs == null ? null : (String) attrs.get("userId");
                boolean authed = attrs != null && Boolean.TRUE.equals(attrs.get("authed"));
                if (authed && userId != null && destination.endsWith("." + userId)) {
                    return message;
                }
                log.warn("[WS] 订阅被拒绝(越权防护): destination={}, authed={}, uid={}", destination, authed, userId);
                throw new MessagingException("订阅无权限: " + destination);
            }
        });
    }
}
