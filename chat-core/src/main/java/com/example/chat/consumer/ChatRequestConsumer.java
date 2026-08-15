package com.example.chat.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
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

    /**
     * 手动 ack：正常处理完 basicAck；反序列化失败等不可重试场景 basicNack(requeue=false)，
     * 避免 AUTO ack 下 catch 异常后消息被静默确认丢弃（数据丢失风险）。
     */
    @RabbitListener(queues = "chat.requests", ackMode = "MANUAL")
    public void handle(Message amqpMessage, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            if (amqpMessage == null || channel == null) {
                log.warn("[WARN] ChatRequestConsumer: null message/channel, skip");
                return;
            }
            Map<String, Object> map = null;
            byte[] body = amqpMessage.getBody();
            if (body != null) {
                String s = new String(body, StandardCharsets.UTF_8);
                map = objectMapper.readValue(s, Map.class);
            }
            if (map == null) {
                log.warn("[WARN] ChatRequestConsumer: message body empty/unparseable, nack & drop");
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            log.debug("[DEBUG] ChatRequestConsumer received payload: {}", map);
            chatProcessor.process(map);
            channel.basicAck(deliveryTag, false);

        } catch (Exception ex) {
            log.error("[ERROR] ChatRequestConsumer failed: {}", ex.getMessage(), ex);
            try {
                // 反序列化/处理失败：不 requeue，避免死循环；由告警日志追踪
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("[ERROR] basicNack failed: {}", nackEx.getMessage());
            }
        }
    }
}
