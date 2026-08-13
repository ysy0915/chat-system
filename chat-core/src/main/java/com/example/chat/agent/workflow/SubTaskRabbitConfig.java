package com.example.chat.agent.workflow;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sub-Agent 工作流 RabbitMQ 基础设施 —— Step 3 并发执行 + 失败重试。
 *
 * <p>复用 chat-common RabbitConfig 的 {@code RabbitTemplate}（Jackson JSON 序列化）
 * 与 {@code rabbitListenerContainerFactory}（并发消费者）：
 * <ul>
 *   <li>{@code agent.subtask.queue}：任务分发队列（core 双实例公平消费，Worker 并行执行，
 *       声明 x-dead-letter-exchange 兜底：意外拒收的消息进入重试链路而非丢失）</li>
 *   <li>{@code agent.subtask.result.queue}：结果回传队列（ResultCollector 收敛聚合）</li>
 *   <li>{@code agent.subtask.exchange}：direct 交换机，按 routing key 分流任务/结果</li>
 *   <li>{@code agent.subtask.dlx} + {@code agent.subtask.dlq}：死信/重试队列，
 *       Worker 失败时带指数退避 TTL 投递到此，到期后经 x-dead-letter-exchange 回到任务队列重试</li>
 * </ul>
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class SubTaskRabbitConfig {

    public static final String EXCHANGE = "agent.subtask.exchange";
    public static final String SUBTASK_QUEUE = "agent.subtask.queue";
    public static final String SUBTASK_RESULT_QUEUE = "agent.subtask.result.queue";
    /** 死信交换机：Worker 失败重试链路 */
    public static final String SUBTASK_DLX = "agent.subtask.dlx";
    /** 重试（死信）队列：带退避 TTL，到期后回到主交换机重试 */
    public static final String SUBTASK_DLQ = "agent.subtask.dlq";
    public static final String ROUTING_SUBTASK = "subtask.run";
    public static final String ROUTING_RESULT = "subtask.result";
    /** DLX → 重试队列 的绑定 key */
    public static final String ROUTING_RETRY = "subtask.retry";
    /**
     * 重试队列兜底 TTL（毫秒）：仅当消息未携带 per-message TTL 时生效
     * （正常路径 Worker 每次重试都会带上指数退避 TTL，兜底值取最大退避间隔）。
     */
    public static final long RETRY_DLQ_TTL_MS = 60000;

    @Bean
    public DirectExchange agentSubtaskExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public DirectExchange agentSubtaskDlx() {
        return new DirectExchange(SUBTASK_DLX);
    }

    @Bean
    public Queue agentSubtaskQueue() {
        return QueueBuilder.durable(SUBTASK_QUEUE)
                .deadLetterExchange(SUBTASK_DLX)
                .deadLetterRoutingKey(ROUTING_RETRY)
                .build();
    }

    @Bean
    public Queue agentSubtaskResultQueue() {
        return new Queue(SUBTASK_RESULT_QUEUE, true);
    }

    @Bean
    public Queue agentSubtaskRetryQueue() {
        return QueueBuilder.durable(SUBTASK_DLQ)
                .ttl((int) RETRY_DLQ_TTL_MS)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(ROUTING_SUBTASK)
                .build();
    }

    @Bean
    public Binding subtaskBinding(Queue agentSubtaskQueue, DirectExchange agentSubtaskExchange) {
        return BindingBuilder.bind(agentSubtaskQueue).to(agentSubtaskExchange).with(ROUTING_SUBTASK);
    }

    @Bean
    public Binding subtaskResultBinding(Queue agentSubtaskResultQueue, DirectExchange agentSubtaskExchange) {
        return BindingBuilder.bind(agentSubtaskResultQueue).to(agentSubtaskExchange).with(ROUTING_RESULT);
    }

    @Bean
    public Binding subtaskRetryBinding(Queue agentSubtaskRetryQueue, DirectExchange agentSubtaskDlx) {
        return BindingBuilder.bind(agentSubtaskRetryQueue).to(agentSubtaskDlx).with(ROUTING_RETRY);
    }
}
