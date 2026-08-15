package com.example.chat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
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

    /**
     * 启动时检查消息队列积压量，仅告警不清理。
     * <p>原实现启动即 purge 队列，双实例滚动重启时会把在途消息直接丢弃（数据丢失风险）。
     * 改为：积压为 0 正常启动；积压 > 0 记录告警，由人工确认积压来源后决定是否清理。</p>
     */
    @Bean
    public CommandLineRunner checkQueueBacklog(RabbitAdmin rabbitAdmin) {
        return args -> {
            Thread checkThread = new Thread(() -> {
                try {
                    QueueInformation info = rabbitAdmin.getQueueInfo(CHAT_REQUESTS_QUEUE);
                    if (info == null) {
                        log.warn("[RabbitConfig] 队列 {} 不存在或无法查询", CHAT_REQUESTS_QUEUE);
                        return;
                    }
                    int backlog = info.getMessageCount();
                    if (backlog > 0) {
                        log.warn("[RabbitConfig] 队列 {} 启动时积压 {} 条消息，"
                                        + "请确认是否来自旧实例在途请求；如需清理请人工执行 rabbitmqctl purge_queue {}",
                                CHAT_REQUESTS_QUEUE, backlog, CHAT_REQUESTS_QUEUE);
                    } else {
                        log.info("[RabbitConfig] 队列 {} 积压为 0，正常启动", CHAT_REQUESTS_QUEUE);
                    }
                } catch (Exception ex) {
                    log.warn("[RabbitConfig] 队列积压检查失败: {}", ex.getMessage());
                }
            });
            checkThread.setDaemon(true);
            checkThread.start();
        };
    }
}

