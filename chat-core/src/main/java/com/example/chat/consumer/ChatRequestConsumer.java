package com.example.chat.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class ChatRequestConsumer {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestConsumer.class);
    private final com.example.chat.service.ChatProcessor chatProcessor;
    private final ObjectMapper objectMapper;

    public ChatRequestConsumer(com.example.chat.service.ChatProcessor chatProcessor, ObjectMapper objectMapper) {
        this.chatProcessor = chatProcessor;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "chat.requests")
    public void handle(org.springframework.amqp.core.Message amqpMessage) {
        try {
            Map<String, Object> map = null;
            if (amqpMessage == null) {
                return;
            }
            byte[] body = amqpMessage.getBody();
            if (body != null) {
                String s = new String(body, StandardCharsets.UTF_8);
                map = objectMapper.readValue(s, Map.class);
            }
            if (map == null) {
                log.warn("[WARN] ChatRequestConsumer: message body was empty or could not be parsed; headers will not be inspected for safety.");
                return;
            }
            log.debug("[DEBUG] ChatRequestConsumer received payload: {}", map);
            chatProcessor.process(map);
            
        } catch (Exception ex) {
            log.error("[ERROR] ChatRequestConsumer failed to parse message: {}", ex.getMessage(), ex);
        }
    }
}
