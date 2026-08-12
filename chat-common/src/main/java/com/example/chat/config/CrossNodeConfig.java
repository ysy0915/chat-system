package com.example.chat.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(ConnectionFactory.class)
@ConditionalOnProperty(name = "app.cross-node.enabled", havingValue = "true")
public class CrossNodeConfig {

    public static final String EXCHANGE = "cross-node";

    @Value("${server.port:8081}")
    private int serverPort;

    @Bean
    public String nodeId() {
        // 固定 nodeId（按端口），避免每次重启生成新队列导致 RabbitMQ 队列堆积
        return "node-" + serverPort;
    }

    @Bean
    public TopicExchange crossNodeExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue crossNodeQueue(String nodeId) {
        return new Queue("cross-node-" + nodeId, true, false, true);
    }

    @Bean
    public Binding crossNodeBinding(Queue crossNodeQueue, TopicExchange crossNodeExchange) {
        return BindingBuilder.bind(crossNodeQueue).to(crossNodeExchange).with("#");
    }

    @Bean
    public SimpleMessageListenerContainer crossNodeListenerContainer(
            ConnectionFactory connectionFactory,
            CrossNodeMessageListener listener) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setQueueNames(crossNodeQueue(nodeId()).getName());
        container.setMessageListener(listener);
        container.setAutoStartup(true);
        return container;
    }
}
