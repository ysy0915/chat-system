package com.example.chat.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.CommandLineRunner;

@Configuration
public class RabbitConfig {
    public static final String CHAT_REQUESTS_QUEUE = "chat.requests";
    public static final String CHAT_EXCHANGE = "chat.exchange";
    public static final String CHAT_ROUTING_KEY = "chat.request";

    @Bean
    public Queue chatRequestsQueue() {
        return new Queue(CHAT_REQUESTS_QUEUE, true);
    }

    @Bean
    public DirectExchange chatExchange() {
        return new DirectExchange(CHAT_EXCHANGE);
    }

    @Bean
    public Binding binding(Queue chatRequestsQueue, DirectExchange chatExchange) {
        return BindingBuilder.bind(chatRequestsQueue).to(chatExchange).with(CHAT_ROUTING_KEY);
    }

    // Use JSON message converter for safety and interoperability
    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        RabbitTemplate rt = new RabbitTemplate(connectionFactory);
        rt.setMessageConverter(converter);
        return rt;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory, Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        return factory;
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public CommandLineRunner purgeOldQueue(RabbitAdmin rabbitAdmin) {
        return args -> {
            try {
                // purge any existing (possibly Java-serialized) messages from the queue to avoid conversion errors
                System.out.println("[INFO] Purging queue: " + CHAT_REQUESTS_QUEUE);
                rabbitAdmin.purgeQueue(CHAT_REQUESTS_QUEUE, true);
            } catch (Exception ex) {
                System.err.println("[WARN] Failed to purge queue " + CHAT_REQUESTS_QUEUE + ": " + ex.getMessage());
            }
        };
    }
}

