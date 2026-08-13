package com.example.chat.service;

import com.example.chat.config.CrossNodeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);
    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public BroadcastService(SimpMessagingTemplate messagingTemplate,
                            @Autowired(required = false) RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper,
                            @Value("${server.port:8080}") int port) {
        this.messagingTemplate = messagingTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = "node-" + port;
    }

    public void broadcast(String destination, Object data) {
        messagingTemplate.convertAndSend(destination, data);
        if (rabbitTemplate == null) return;
        try {
            Map<String, Object> payload = Map.of(
                    "_nodeId", nodeId,
                    "destination", destination,
                    "data", data
            );
            // 直接发送 JSON 字符串，与 CrossNodeMessageListener 的解析方式匹配
            String json = objectMapper.writeValueAsString(payload);
            rabbitTemplate.convertAndSend(CrossNodeConfig.EXCHANGE, destination, json);
        } catch (Exception e) {
            log.warn("[CrossNode] RabbitMQ 发布失败: {}", e.getMessage());
        }
    }
}
