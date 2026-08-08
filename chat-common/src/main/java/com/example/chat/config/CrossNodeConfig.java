package com.example.chat.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
@ConditionalOnClass(ConnectionFactory.class)
@ConditionalOnProperty(name = "app.cross-node.enabled", havingValue = "true")
public class CrossNodeConfig {

    public static final String EXCHANGE = "cross-node";

    @Value("${server.port:8081}")
    private int serverPort;

    @Bean
    public String nodeId() {
        return "node-" + serverPort + "-" + UUID.randomUUID().toString().substring(0, 8);
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
