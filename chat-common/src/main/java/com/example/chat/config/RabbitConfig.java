package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;

@Configuration
@ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class RabbitConfig {
    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);
    public static final String CHAT_REQUESTS_QUEUE = "chat.requests";
    public static final String CHAT_EXCHANGE = "chat.exchange";
    public static final String CHAT_ROUTING_KEY = "chat.request";

    /** RabbitMQ 监听并发（Multi-Agent Worker 数量 = concurrentConsumers × core 实例数） */
    @Value("${spring.rabbitmq.listener.simple.concurrency:10}")
    private int listenerConcurrency;

    @Value("${spring.rabbitmq.listener.simple.max-concurrency:20}")
    private int listenerMaxConcurrency;

    @Value("${spring.rabbitmq.listener.simple.prefetch:5}")
    private int listenerPrefetch;

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
        factory.setConcurrentConsumers(listenerConcurrency);
        factory.setMaxConcurrentConsumers(listenerMaxConcurrency);
        factory.setPrefetchCount(listenerPrefetch);
        log.info("[RabbitConfig] Listener 并发初始化 concurrency={} max-concurrency={} prefetch={}",
                listenerConcurrency, listenerMaxConcurrency, listenerPrefetch);
        return factory;
    }

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    public CommandLineRunner purgeOldQueue(RabbitAdmin rabbitAdmin) {
        return args -> {
            Thread purgeThread = new Thread(() -> {
                try {
                    log.info("[INFO] Purging queue: {}", CHAT_REQUESTS_QUEUE);
                    rabbitAdmin.purgeQueue(CHAT_REQUESTS_QUEUE, true);
                    log.info("[INFO] Queue purged successfully");
                } catch (Exception ex) {
                    log.warn("[WARN] Failed to purge queue: {}", ex.getMessage());
                }
            });
            purgeThread.setDaemon(true);
            purgeThread.start();
        };
    }
}

