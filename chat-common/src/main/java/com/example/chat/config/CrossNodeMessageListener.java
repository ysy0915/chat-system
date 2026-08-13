package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.connection.ConnectionFactory")
@ConditionalOnProperty(name = "app.cross-node.enabled", havingValue = "true")
public class CrossNodeMessageListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(CrossNodeMessageListener.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public CrossNodeMessageListener(SimpMessagingTemplate messagingTemplate,
                                     ObjectMapper objectMapper,
                                     String nodeId) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    @Override
    public void onMessage(Message message) {
        try {
            String json = new String(message.getBody()).trim();
            // RabbitTemplate 发送的可能是 String 或 byte[]，统一处理
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = objectMapper.readValue(json, String.class);
            }
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            String sourceNode = (String) payload.get("_nodeId");
            if (nodeId.equals(sourceNode)) {
                return;
            }
            String destination = (String) payload.get("destination");
            Object data = payload.get("data");
            messagingTemplate.convertAndSend(destination, data);
        } catch (Exception e) {
            log.warn("[CrossNode] 消息处理失败: {}", e.getMessage());
        }
    }
}
