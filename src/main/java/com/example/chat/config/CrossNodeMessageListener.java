package com.example.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;

@Component
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
            String raw = new String(message.getBody()).trim();
            String json;
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                json = objectMapper.readValue(raw, String.class);
            } else {
                json = raw;
            }
            byte[] decoded = Base64.getDecoder().decode(json);
            Map<String, Object> payload = objectMapper.readValue(decoded, Map.class);
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
