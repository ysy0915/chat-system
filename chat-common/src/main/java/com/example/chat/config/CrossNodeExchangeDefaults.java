package com.example.chat.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * cross-node 交换机的无条件声明（与 app.cross-node.enabled 无关）。
 *
 * <p>根因修复：{@code BroadcastService#broadcast} 只要存在 RabbitTemplate 就会向
 * {@code cross-node} 交换机发布（用于双 web 实例间的 STOMP 广播同步），但该交换机此前
 * 仅在 {@code app.cross-node.enabled=true} 时由 {@link CrossNodeConfig} 声明。单机部署
 * （开关关闭）时交换机不存在，每次广播都触发 RabbitMQ channel error 刷 ERROR 日志，
 * 且需要人工在 broker 上补建交换机。</p>
 *
 * <p>声明是幂等的（同名同参数），与 CrossNodeConfig 的声明参数保持一致，
 * 开关开启时两边声明等价，不会冲突。</p>
 */
@Configuration
@ConditionalOnClass(ConnectionFactory.class)
public class CrossNodeExchangeDefaults {

    @Bean
    public TopicExchange crossNodeExchangeAlways() {
        // 与 CrossNodeConfig#crossNodeExchange 的 new TopicExchange(EXCHANGE) 参数一致：
        // durable=true, autoDelete=false
        return new TopicExchange(CrossNodeConfig.EXCHANGE, true, false);
    }
}
