package com.example.chat.agent.workflow;

import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.tool.Tool;
import com.example.chat.agent.tool.ToolRegistry;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.ApiKeyResolver;
import com.example.chat.util.BaseUrlResolver;
import com.example.chat.util.LlmToolInvoker;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子代理 Worker —— Step 3 并发执行。
 *
 * <p>从 {@code agent.subtask.queue} 消费 {@link SubAgentTask}，使用<b>独立的极短上下文</b>
 * （仅 instructions + context_summary）执行：</p>
 * <ul>
 *   <li>tools_scope 为空：直接 LLM 非流式产出结构化摘要；</li>
 *   <li>tools_scope 非空：按范围注入限定工具，最多 1 轮工具调用后产出摘要；</li>
 *   <li>执行完毕仅将 {@link SubAgentResult} 结构化摘要回传结果队列，不携带上下文。</li>
 * </ul>
 * <p><b>失败重试</b>：执行失败不再直接回传终态失败，而是按 {@code x-death}
 * 累计重试次数做指数退避（1s→2s→4s→…→60s，默认最多 5 次执行），
 * 经 {@link SubTaskRabbitConfig#SUBTASK_DLX} 死信队列延迟重试；
 * 达到上限后才回传终态失败结果（ack，不重试）。</p>
 */
@Component
@ConditionalOnProperty(name = "app.agent.planner.enabled", havingValue = "true")
public class SubAgentWorker {

    private static final Logger log = LoggerFactory.getLogger(SubAgentWorker.class);

    private final LLMInvoker llmInvoker;
    private final LlmConfigProperties llmConfig;
    private final ToolRegistry toolRegistry;
    private final BaseUrlResolver baseUrlResolver;
    private final LlmToolInvoker llmToolInvoker;
    private final SubTaskProducer subTaskProducer;

    /** 失败重试初始退避（毫秒），指数 1s→2s→4s… */
    @Value("${app.agent.planner.retry.initial-delay-ms:1000}")
    private long retryInitialDelayMs;
    /** 失败重试退避上限（毫秒），与重试队列兜底 TTL 保持一致 */
    @Value("${app.agent.planner.retry.max-delay-ms:60000}")
    private long retryMaxDelayMs;
    /** 最大执行次数（含首次），超过后回传终态失败不再重试 */
    @Value("${app.agent.planner.retry.max-attempts:5}")
    private int retryMaxAttempts;

    @Autowired
    public SubAgentWorker(LLMInvoker llmInvoker,
                          LlmConfigProperties llmConfig,
                          ToolRegistry toolRegistry,
                          BaseUrlResolver baseUrlResolver,
                          LlmToolInvoker llmToolInvoker,
                          SubTaskProducer subTaskProducer) {
        this.llmInvoker = llmInvoker;
        this.llmConfig = llmConfig;
        this.toolRegistry = toolRegistry;
        this.baseUrlResolver = baseUrlResolver;
        this.llmToolInvoker = llmToolInvoker;
        this.subTaskProducer = subTaskProducer;
    }

    @RabbitListener(queues = SubTaskRabbitConfig.SUBTASK_QUEUE, ackMode = "MANUAL")
    public void onSubTask(SubAgentTask task, Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeath) {
        long start = System.currentTimeMillis();
        if (task == null) {
            basicAck(channel, deliveryTag);
            return;
        }
        try {
            String modelName = llmConfig.getModel();
            String summary = execute(task);
            subTaskProducer.sendResult(SubAgentResult.success(task, summary, modelName,
                    System.currentTimeMillis() - start));
            log.info("[SubAgentWorker] 任务完成 taskId={} summaryLen={} cost={}ms",
                    task.taskId, summary.length(), System.currentTimeMillis() - start);
            basicAck(channel, deliveryTag);
        } catch (Exception e) {
            // 读 x-death 累计死亡次数 = 已重试次数（每次 DLQ 到期重投 +1）
            int deathCount = deathCount(xDeath);
            int attempt = deathCount + 1;
            if (attempt >= retryMaxAttempts) {
                // 终态失败：回传失败结果 + ack（不再重试），收敛侧将该子任务标记为失败
                log.error("[SubAgentWorker] 任务执行失败已达上限 attempt={}/{} taskId={}: {}",
                        attempt, retryMaxAttempts, task.taskId, e.getMessage(), e);
                subTaskProducer.sendResult(SubAgentResult.failure(task, e.getMessage(),
                        System.currentTimeMillis() - start));
                basicAck(channel, deliveryTag);
            } else {
                // 可重试：发布到 DLX（带指数退避 TTL）+ ack 原消息，到期后回到任务队列重试
                long delay = backoffDelayMs(deathCount);
                log.warn("[SubAgentWorker] 任务执行失败 attempt={}/{} taskId={}，进入死信重试 delay={}ms: {}",
                        attempt, retryMaxAttempts, task.taskId, delay, e.getMessage());
                subTaskProducer.sendRetry(task, delay);
                basicAck(channel, deliveryTag);
            }
        }
    }

    /**
     * 读取消息 {@code x-death} 头中的累计死亡次数（每次在重试队列到期重投 +1），
     * 即该任务已重试的次数；无该头表示首次执行。
     */
    int deathCount(List<Map<String, Object>> xDeath) {
        if (xDeath == null || xDeath.isEmpty()) return 0;
        int count = 0;
        for (Map<String, Object> entry : xDeath) {
            Object value = entry.get("count");
            if (value instanceof Number n) {
                count = Math.max(count, n.intValue());
            }
        }
        return count;
    }

    /** 指数退避：initial * 2^retry，封顶 max（1s→2s→4s→…→60s） */
    long backoffDelayMs(int deathCount) {
        long delay = retryInitialDelayMs * (long) Math.pow(2, deathCount);
        return Math.min(delay, retryMaxDelayMs);
    }

    /** 手动确认：成功处理，通知 RabbitMQ 可继续派发下一条 */
    private void basicAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("[SubAgentWorker] basicAck 失败 tag={}: {}", deliveryTag, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  执行
    // ═══════════════════════════════════════════════════════════════════

    private String execute(SubAgentTask task) throws Exception {
        ModelConfig config = defaultConfig();
        List<LLMMessage> messages = buildMessages(task);
        List<Map<String, Object>> tools = toolRegistry.getToolsSchema(task.toolsScope);

        if (tools.isEmpty()) {
            return llmInvoker.invoke(config, messages, 0.2, "subagent",
                    llmConfig.getBaseUrl(), llmConfig.getApiKey());
        }
        return callWithToolsOnce(config, messages, tools);
    }

    /** 极短独立上下文：仅子任务指令 + 相关上下文 + 期望输出 */
    private List<LLMMessage> buildMessages(SubAgentTask task) {
        StringBuilder system = new StringBuilder();
        system.append("你是一名子代理（Sub-Agent），由主 Agent 分派处理一个独立子任务。\n")
                .append("你只看到与本任务直接相关的上下文，看不到完整对话历史。\n")
                .append("请严格按任务指令执行，最终输出结构化摘要（简洁、要点化、可被主 Agent 直接汇总引用）。\n");
        if (task.toolsScope != null && !task.toolsScope.isEmpty()) {
            system.append("本任务允许使用工具：").append(task.toolsScope).append("，按需调用。\n");
        }
        StringBuilder user = new StringBuilder();
        user.append("【任务指令】\n").append(task.instructions).append("\n\n");
        if (task.contextSummary != null && !task.contextSummary.isBlank()) {
            user.append("【相关上下文】\n").append(task.contextSummary).append("\n\n");
        }
        if (task.expectedOutput != null && !task.expectedOutput.isBlank()) {
            user.append("【期望输出】\n").append(task.expectedOutput).append('\n');
        }
        return List.of(new LLMMessage("system", system.toString()),
                new LLMMessage("user", user.toString()));
    }

    /** 单轮限定工具调用：LLM 选工具 → 执行 → 回填 → 再调 LLM 产出最终摘要 */
    @SuppressWarnings("unchecked")
    private String callWithToolsOnce(ModelConfig config, List<LLMMessage> messages,
                                     List<Map<String, Object>> tools) throws Exception {
        String baseUrl = baseUrlResolver.resolve(config, llmConfig.getBaseUrl());
        String apiKey = ApiKeyResolver.resolve(config, llmConfig.getApiKey());

        Map<String, Object> llmResp = llmToolInvoker.callWithTools(config, baseUrl, apiKey, messages, 0.2, tools);
        List<Map<String, Object>> toolCalls = llmToolInvoker.extractToolCalls(llmResp);
        String content = llmToolInvoker.extractContent(llmResp);
        if (toolCalls == null || toolCalls.isEmpty()) {
            return content != null ? content : "";
        }

        // assistant(带 tool_calls) + 工具结果回填
        List<Map<String, Object>> working = new ArrayList<>(LLMMessage.toMapList(messages));
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", content != null ? content : "");
        assistantMsg.put("tool_calls", toolCalls);
        working.add(assistantMsg);

        for (Map<String, Object> tc : toolCalls) {
            String toolResult = llmToolInvoker.executeOneToolCall(tc, this::executeTool);
            String toolName = llmToolInvoker.toolNameOf(tc);
            Map<String, Object> toolMsg = new LinkedHashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("name", toolName);
            toolMsg.put("content", toolResult);
            working.add(toolMsg);
        }

        return llmInvoker.invoke(config, LlmToolInvoker.fromMapList(working), 0.2, "subagent",
                llmConfig.getBaseUrl(), llmConfig.getApiKey());
    }

    /** 从工具注册中心按名执行工具；未知工具返回占位提示 */
    private String executeTool(String toolName, Map<String, Object> params) {
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("[SubAgentWorker] 未知工具: {}", toolName);
            return "[未知工具: " + toolName + "]";
        }
        String result = tool.execute(params);
        return result != null ? result : "";
    }

    private ModelConfig defaultConfig() {
        ModelConfig c = new ModelConfig();
        c.provider = llmConfig.getProvider();
        c.model = llmConfig.getModel();
        c.apiKeyEncrypted = llmConfig.getApiKey();
        return c;
    }
}
