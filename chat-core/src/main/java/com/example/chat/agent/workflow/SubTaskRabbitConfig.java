package com.example.chat.agent.workflow;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sub-Agent 工作流 RabbitMQ 基础设施 —— Step 3 并发执行。
 *
 * <p>复用 chat-common RabbitConfig 的 {@code RabbitTemplate}（Jackson JSON 序列化）
 * 与 {@code rabbitListenerContainerFactory}（并发消费者）：
 * <ul>
 *   <li>{@code agent.subtask.queue}：任务分发队列（core 双实例公平消费，Worker 并行执行）</li>
 *   <li>{@code agent.subtask.result.queue}：结果回传队列（ResultCollector 收敛聚合）</li>
 *   <li>{@code agent.subtask.exchange}：direct 交换机，按 routing key 分流任务/结果</li>
 * </ul>
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class SubTaskRabbitConfig {

    public static final String EXCHANGE = "agent.subtask.exchange";
    public static final String SUBTASK_QUEUE = "agent.subtask.queue";
    public static final String SUBTASK_RESULT_QUEUE = "agent.subtask.result.queue";
    public static final String ROUTING_SUBTASK = "subtask.run";
    public static final String ROUTING_RESULT = "subtask.result";

    @Bean
    public DirectExchange agentSubtaskExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue agentSubtaskQueue() {
        return new Queue(SUBTASK_QUEUE, true);
    }

    @Bean
    public Queue agentSubtaskResultQueue() {
        return new Queue(SUBTASK_RESULT_QUEUE, true);
    }

    @Bean
    public Binding subtaskBinding(Queue agentSubtaskQueue, DirectExchange agentSubtaskExchange) {
        return BindingBuilder.bind(agentSubtaskQueue).to(agentSubtaskExchange).with(ROUTING_SUBTASK);
    }

    @Bean
    public Binding subtaskResultBinding(Queue agentSubtaskResultQueue, DirectExchange agentSubtaskExchange) {
        return BindingBuilder.bind(agentSubtaskResultQueue).to(agentSubtaskExchange).with(ROUTING_RESULT);
    }
}
