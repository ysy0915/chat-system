package com.example.chat.consumer;

import com.example.chat.entity.Message;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

@Component
public class ChatRequestConsumer {
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
                System.err.println("[WARN] ChatRequestConsumer: message body was empty or could not be parsed; headers will not be inspected for safety.");
                return;
            }
            System.out.println("[DEBUG] ChatRequestConsumer received payload: " + map);
            chatProcessor.process(map);
            
        } catch (Exception ex) {
            System.err.println("[ERROR] ChatRequestConsumer failed to parse message: " + ex.getMessage());
            ex.printStackTrace();
        }
    }
}
