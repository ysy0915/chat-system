package com.example.chat.agent.workflow;

import com.example.chat.agent.protocol.SubAgentResult;
import com.example.chat.agent.protocol.SubAgentTask;
import com.example.chat.agent.tool.ToolRegistry;
import com.example.chat.config.LlmConfigProperties;
import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
    private final ObjectMapper objectMapper;
    private final SubTaskProducer subTaskProducer;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Autowired
    public SubAgentWorker(LLMInvoker llmInvoker,
                          LlmConfigProperties llmConfig,
                          ToolRegistry toolRegistry,
                          BaseUrlResolver baseUrlResolver,
                          ObjectMapper objectMapper,
                          SubTaskProducer subTaskProducer) {
        this.llmInvoker = llmInvoker;
        this.llmConfig = llmConfig;
        this.toolRegistry = toolRegistry;
        this.baseUrlResolver = baseUrlResolver;
        this.objectMapper = objectMapper;
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

        Map<String, Object> llmResp = callLLMWithTools(config, baseUrl, apiKey, messages, tools);
        List<Map<String, Object>> toolCalls = extractToolCalls(llmResp);
        String content = extractContent(llmResp);
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
            String toolResult = executeOneToolCall(tc);
            String toolName = toolNameOf(tc);
            Map<String, Object> toolMsg = new LinkedHashMap<>();
            toolMsg.put("role", "tool");
            toolMsg.put("name", toolName);
            toolMsg.put("content", toolResult);
            working.add(toolMsg);
        }

        return llmInvoker.invoke(config, fromMapList(working), 0.2, "subagent",
                llmConfig.getBaseUrl(), llmConfig.getApiKey());
    }

    /** 将 Map 消息列表转回 LLMMessage */
    private List<LLMMessage> fromMapList(List<Map<String, Object>> maps) {
        List<LLMMessage> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            result.add(LLMMessage.fromMap(m));
        }
        return result;
    }

    /** 带限定 tools 调 LLM（OpenAI 兼容 /chat/completions） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callLLMWithTools(ModelConfig config, String baseUrl, String apiKey,
                                                 List<LLMMessage> messages,
                                                 List<Map<String, Object>> tools) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.model);
        requestBody.put("messages", LLMMessage.toMapList(messages));
        requestBody.put("temperature", 0.2);
        requestBody.put("tools", tools);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new LLMCallException(response.statusCode(),
                    "SubAgentWorker LLM API returned status " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    // ═══════════════════════════════════════════════════════════════════
    //  工具执行（与 ToolDispatcher 相同的 OpenAI function calling 解析）
    // ═══════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> llmResp) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) llmResp.get("choices");
            if (choices == null || choices.isEmpty()) return Collections.emptyList();
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return Collections.emptyList();
            Object toolCalls = message.get("tool_calls");
            if (toolCalls instanceof List) {
                return (List<Map<String, Object>>) toolCalls;
            }
        } catch (Exception e) {
            log.warn("[SubAgentWorker] 解析 tool_calls 失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    private String extractContent(Map<String, Object> llmResp) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) llmResp.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return null;
            Object content = message.get("content");
            return content != null ? content.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String toolNameOf(Map<String, Object> toolCall) {
        if (toolCall == null) return "tool";
        Object fn = toolCall.get("function");
        if (fn instanceof Map) {
            Object name = ((Map<?, ?>) fn).get("name");
            if (name != null) return name.toString();
        }
        return "tool";
    }

    private String executeOneToolCall(Map<String, Object> toolCall) {
        String toolName = toolNameOf(toolCall);
        Object fn = toolCall.get("function");
        if (!(fn instanceof Map)) return "[工具调用格式错误]";
        Map<?, ?> function = (Map<?, ?>) fn;
        Object arguments = function.get("arguments");

        Map<String, Object> params = new LinkedHashMap<>();
        if (arguments instanceof String) {
            try {
                Object parsed = objectMapper.readValue((String) arguments, Object.class);
                if (parsed instanceof Map) {
                    params.putAll((Map<String, Object>) parsed);
                }
            } catch (Exception e) {
                log.warn("[SubAgentWorker] 解析工具 {} 参数失败: {}", toolName, e.getMessage());
            }
        } else if (arguments instanceof Map) {
            params.putAll((Map<String, Object>) arguments);
        }

        try {
            String result = toolRegistry.getTool(toolName).execute(params);
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("[SubAgentWorker] 工具 {} 执行失败: {}", toolName, e.getMessage());
            return "[工具 " + toolName + " 执行失败: " + e.getMessage() + "]";
        }
    }

    private ModelConfig defaultConfig() {
        ModelConfig c = new ModelConfig();
        c.provider = llmConfig.getProvider();
        c.model = llmConfig.getModel();
        c.apiKeyEncrypted = llmConfig.getApiKey();
        return c;
    }
}
