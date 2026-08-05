package com.example.chat.service;

import com.example.chat.config.CrossNodeConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
                            RabbitTemplate rabbitTemplate,
                            ObjectMapper objectMapper,
                            String nodeId) {
        this.messagingTemplate = messagingTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    public void broadcast(String destination, Object data) {
        messagingTemplate.convertAndSend(destination, data);
        try {
            Map<String, Object> payload = Map.of(
                    "_nodeId", nodeId,
                    "destination", destination,
                    "data", data
            );
            rabbitTemplate.convertAndSend(CrossNodeConfig.EXCHANGE, destination, 
                    objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            log.warn("[CrossNode] RabbitMQ 发布失败: {}", e.getMessage());
        }
    }
}
