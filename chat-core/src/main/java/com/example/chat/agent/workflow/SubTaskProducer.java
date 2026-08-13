package com.example.chat.agent.workflow;

import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Sub-Agent 消息生产者 —— Step 3。
 *
 * <p>Orchestrator 分发子任务、Worker 回传结果，均经此发送到 RabbitMQ。
 * 序列化复用 chat-common RabbitConfig 的 Jackson2JsonMessageConverter。</p>
 */
@Service
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class SubTaskProducer {

    private static final Logger log = LoggerFactory.getLogger(SubTaskProducer.class);

    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public SubTaskProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 分发单个子任务到 Worker 队列 */
    public void sendTask(SubAgentTask task) {
        try {
            rabbitTemplate.convertAndSend(SubTaskRabbitConfig.EXCHANGE,
                    SubTaskRabbitConfig.ROUTING_SUBTASK, task);
            log.info("[SubTask] 子任务已分发 taskId={} planId={} toolsScope={}",
                    task.taskId, task.planId, task.toolsScope);
        } catch (Exception e) {
            log.error("[SubTask] 子任务分发失败 taskId={}: {}", task.taskId, e.getMessage(), e);
            throw new IllegalStateException("子任务分发失败: " + task.taskId, e);
        }
    }

    /**
     * 失败重试：将子任务投递到死信交换机（DLX），携带指数退避 TTL（毫秒）。
     * 到期后经 {@code agent.subtask.dlq} 的 x-dead-letter-exchange 回到任务队列重新执行。
     */
    public void sendRetry(SubAgentTask task, long delayMs) {
        try {
            rabbitTemplate.convertAndSend(SubTaskRabbitConfig.SUBTASK_DLX,
                    SubTaskRabbitConfig.ROUTING_RETRY, task,
                    m -> {
                        m.getMessageProperties().setExpiration(String.valueOf(delayMs));
                        return m;
                    });
            log.info("[SubTask] 子任务进入重试队列 taskId={} delay={}ms", task.taskId, delayMs);
        } catch (Exception e) {
            log.error("[SubTask] 子任务重试入队失败 taskId={}: {}", task.taskId, e.getMessage());
        }
    }

    /** Worker 回传执行结果到结果队列 */
    public void sendResult(SubAgentResult result) {
        try {
            rabbitTemplate.convertAndSend(SubTaskRabbitConfig.EXCHANGE,
                    SubTaskRabbitConfig.ROUTING_RESULT, result);
            log.debug("[SubTask] 结果已回传 taskId={} success={} summaryLen={}",
                    result.taskId, result.success,
                    result.summary != null ? result.summary.length() : 0);
        } catch (Exception e) {
            log.error("[SubTask] 结果回传失败 taskId={}: {}", result.taskId, e.getMessage());
        }
    }
}
