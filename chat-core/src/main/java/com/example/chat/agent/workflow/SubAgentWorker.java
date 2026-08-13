package com.example.chat.agent.workflow;

import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.tool.Tool;
import com.example.chat.agent.tool.ToolRegistry;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.BaseUrlResolver;
import com.example.chat.util.LlmToolInvoker;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
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
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        long start = System.currentTimeMillis();
        if (task == null) {
            basicAck(channel, deliveryTag);
            return;
        }
        String modelName = "unknown";
        try {
            modelName = llmConfig.getModel();
            String summary = execute(task);
            subTaskProducer.sendResult(SubAgentResult.success(task, summary, modelName,
                    System.currentTimeMillis() - start));
            log.info("[SubAgentWorker] 任务完成 taskId={} summaryLen={} cost={}ms",
                    task.taskId, summary.length(), System.currentTimeMillis() - start);
            basicAck(channel, deliveryTag);
        } catch (Exception e) {
            log.error("[SubAgentWorker] 任务执行失败 taskId={}: {}", task.taskId, e.getMessage(), e);
            subTaskProducer.sendResult(SubAgentResult.failure(task, e.getMessage(),
                    System.currentTimeMillis() - start));
            // requeue=false：失败结果已回传（收敛会标记该子任务失败），避免无限重试导致计数重复
            basicNack(channel, deliveryTag, false);
        }
    }

    /** 手动确认：成功处理，通知 RabbitMQ 可继续派发下一条 */
    private void basicAck(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException e) {
            log.warn("[SubAgentWorker] basicAck 失败 tag={}: {}", deliveryTag, e.getMessage());
        }
    }

    /** 手动拒收：requeue=true 时消息重新入队（如结果回传瞬时失败需重试） */
    private void basicNack(Channel channel, long deliveryTag, boolean requeue) {
        try {
            channel.basicNack(deliveryTag, false, requeue);
        } catch (IOException e) {
            log.warn("[SubAgentWorker] basicNack 失败 tag={}: {}", deliveryTag, e.getMessage());
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
            user.append("【期望输出】\n").append(task.expectedOutput).append("\n");
        }
        return List.of(new LLMMessage("system", system.toString()),
                new LLMMessage("user", user.toString()));
    }

    /** 单轮限定工具调用：LLM 选工具 → 执行 → 回填 → 再调 LLM 产出最终摘要 */
    @SuppressWarnings("unchecked")
    private String callWithToolsOnce(ModelConfig config, List<LLMMessage> messages,
                                     List<Map<String, Object>> tools) throws Exception {
        String baseUrl = baseUrlResolver.resolve(config, llmConfig.getBaseUrl());
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : llmConfig.getApiKey();

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
