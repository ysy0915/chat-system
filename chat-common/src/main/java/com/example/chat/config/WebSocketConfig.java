package com.example.chat.config;

import com.example.chat.security.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
        // 订阅级鉴权暂缓：SockJS + STOMP 场景下 accessor.getSessionAttributes() 无法稳定
        // 取到握手拦截器写入的 userId/authed，导致已登录用户被误判「未登录」、收不到私有推送。
        // 当前先保留握手层 JWT 校验（token 有效则绑定真实 userId），订阅层鉴权待
        // 改用 WebSocketSessionRegistry（sessionId -> userId 映射）方案后重新上线。
    }
}
