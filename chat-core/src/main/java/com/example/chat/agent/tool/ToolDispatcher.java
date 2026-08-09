package com.example.chat.agent.tool;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.service.LLMInvoker;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具调度器
 *
 * 协调 LLM 与工具的调用流程：
 *   1. 把工具列表（function schema）加入请求的 tools 参数
 *   2. 调用 LLM（千问支持 function calling）
 *   3. 如果 LLM 返回 tool_calls，执行对应工具
 *   4. 把工具结果加入 messages 再调 LLM 生成最终回答
 *   5. 如果没有 tool_calls，直接返回 LLM 回答
 *
 * 受 app.agent.enabled=true 控制
 */
@Service
@ConditionalOnProperty(name = "app.agent.enabled", havingValue = "true")
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final ToolRegistry toolRegistry;
    private final LLMInvoker llmInvoker;
    private final BaseUrlResolver baseUrlResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Value("${app.agent.max-tool-calls:3}")
    private int maxToolCalls;

    @Autowired
    public ToolDispatcher(ToolRegistry toolRegistry,
                          LLMInvoker llmInvoker,
                          BaseUrlResolver baseUrlResolver) {
        this.toolRegistry = toolRegistry;
        this.llmInvoker = llmInvoker;
        this.baseUrlResolver = baseUrlResolver;
    }

    /**
     * 工具调度主入口（非流式）
     *
     * @param userInput   用户原始输入（用于日志，可为 null）
     * @param config      模型配置
     * @param messages    当前对话消息列表（会被本方法修改/扩展）
     * @param temperature 温度
     * @param scene       调用场景
     * @param defaultBaseUrl 默认 baseUrl
     * @param defaultApiKey  默认 apiKey
     * @return 最终回答（如果触发了工具，则是工具增强后的回答）
     */
    public String dispatch(String userInput, ModelConfig config,
                           List<LLMMessage> messages,
                           double temperature, String scene,
                           String defaultBaseUrl, String defaultApiKey) throws Exception {
        // 没有注册工具：直接走普通 LLM 调用，保持原行为
        if (!toolRegistry.hasTools()) {
            log.debug("[ToolDispatcher] 无可用工具，直接调用 LLM");
            return llmInvoker.invoke(config, messages, temperature, scene, defaultBaseUrl, defaultApiKey);
        }

        List<Map<String, Object>> toolsSchema = toolRegistry.getToolsSchema();

        // 使用消息的副本进行多轮工具调用 (转为 Map 给底层 API)
        List<Map<String, Object>> workingMessages = new ArrayList<>(LLMMessage.toMapList(messages));

        String baseUrl = baseUrlResolver.resolve(config, defaultBaseUrl);
        String apiKey = (config.apiKeyEncrypted != null && !config.apiKeyEncrypted.isBlank())
                ? config.apiKeyEncrypted : defaultApiKey;

        int callCount = 0;
        while (callCount < maxToolCalls) {
            // 带工具调用 LLM
            Map<String, Object> llmResp = callLLMWithTools(config, baseUrl, apiKey, workingMessages,
                    temperature, toolsSchema);

            List<Map<String, Object>> toolCalls = extractToolCalls(llmResp);
            String assistantContent = extractContent(llmResp);

            if (toolCalls == null || toolCalls.isEmpty()) {
                // LLM 未请求工具，直接返回内容
                log.info("[ToolDispatcher] LLM 未触发工具调用，直接返回 (callCount={}, scene={})", callCount, scene);
                return assistantContent != null ? assistantContent : "";
            }

            // 把 assistant 这条带 tool_calls 的消息加入 messages
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            if (assistantContent != null && !assistantContent.isEmpty()) {
                assistantMsg.put("content", assistantContent);
            } else {
                assistantMsg.put("content", "");
            }
            assistantMsg.put("tool_calls", toolCalls);
            workingMessages.add(assistantMsg);

            // 执行每一个工具调用，把结果作为 tool 角色消息回填
            for (Map<String, Object> tc : toolCalls) {
                String toolResult = executeOneToolCall(tc);
                Map<String, Object> functionCall = asFunction(tc);
                String toolCallId = functionCall != null ? String.valueOf(functionCall.get("name")) : "tool";

                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("name", toolCallId);
                toolMsg.put("content", toolResult);
                workingMessages.add(toolMsg);
            }

            callCount++;
            log.info("[ToolDispatcher] 工具调用第 {} 轮完成，执行了 {} 个工具 (scene={})",
                    callCount, toolCalls.size(), scene);

            // 调用 LLM 生成最终回答（仍带 tools，允许 LLM 继续调用工具直到 maxToolCalls）
            String finalAnswer = llmInvoker.invoke(config, fromMapList(workingMessages), temperature, scene,
                    defaultBaseUrl, defaultApiKey);
            return finalAnswer;
        }

        log.warn("[ToolDispatcher] 达到最大工具调用次数 {}，停止 (scene={})", maxToolCalls, scene);
        // 超过上限：用最后一条消息直接调 LLM（不带工具，强制输出文本）
        return llmInvoker.invoke(config, fromMapList(workingMessages), temperature, scene, defaultBaseUrl, defaultApiKey);
    }

    /** 将 Map 消息列表转回 LLMMessage */
    private List<LLMMessage> fromMapList(List<Map<String, Object>> maps) {
        List<LLMMessage> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            result.add(LLMMessage.fromMap(m));
        }
        return result;
    }

    /**
     * 调用 LLM（带 tools 参数），返回完整响应 map（包含 choices[0].message）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> callLLMWithTools(ModelConfig config, String baseUrl, String apiKey,
                                                 List<Map<String, Object>> messages,
                                                 double temperature,
                                                 List<Map<String, Object>> toolsSchema) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("tools", toolsSchema);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("ToolDispatcher LLM API returned status "
                    + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readValue(response.body(), Map.class);
    }

    /** 从 LLM 响应中提取 choices[0].message.tool_calls */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractToolCalls(Map<String, Object> llmResp) {
        try {
            List<Map<String, Object>> choices = (List<Map<String, Object>>) llmResp.get("choices");
            if (choices == null || choices.isEmpty()) return null;
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) return null;
            Object toolCalls = message.get("tool_calls");
            if (toolCalls instanceof List) {
                return (List<Map<String, Object>>) toolCalls;
            }
        } catch (Exception e) {
            log.warn("[ToolDispatcher] 解析 tool_calls 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 LLM 响应中提取 choices[0].message.content */
    @SuppressWarnings("unchecked")
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

    /** 取 tool_call 中的 function 子对象 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asFunction(Map<String, Object> toolCall) {
        if (toolCall == null) return null;
        Object fn = toolCall.get("function");
        return fn instanceof Map ? (Map<String, Object>) fn : null;
    }

    /**
     * 执行单个 tool_call
     * tool_call 结构：{"id":..., "type":"function", "function":{"name":..., "arguments":"<json string>"}}
     */
    private String executeOneToolCall(Map<String, Object> toolCall) {
        Map<String, Object> function = asFunction(toolCall);
        if (function == null) {
            return "[工具调用格式错误: 缺少 function 字段]";
        }
        String toolName = String.valueOf(function.get("name"));
        Tool tool = toolRegistry.getTool(toolName);
        if (tool == null) {
            log.warn("[ToolDispatcher] 未知工具: {}", toolName);
            return "[未知工具: " + toolName + "]";
        }

        Map<String, Object> params = new LinkedHashMap<>();
        Object arguments = function.get("arguments");
        if (arguments instanceof String) {
            try {
                Object parsed = objectMapper.readValue((String) arguments, Object.class);
                if (parsed instanceof Map) {
                    params.putAll((Map<String, Object>) parsed);
                }
            } catch (Exception e) {
                log.warn("[ToolDispatcher] 解析工具 {} 参数失败: {}", toolName, e.getMessage());
            }
        } else if (arguments instanceof Map) {
            params.putAll((Map<String, Object>) arguments);
        }

        try {
            String result = tool.execute(params);
            log.info("[ToolDispatcher] 工具 {} 执行成功 resultLen={}", toolName,
                    result != null ? result.length() : 0);
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("[ToolDispatcher] 工具 {} 执行失败: {}", toolName, e.getMessage(), e);
            return "[工具 " + toolName + " 执行失败: " + e.getMessage() + "]";
        }
    }
}
