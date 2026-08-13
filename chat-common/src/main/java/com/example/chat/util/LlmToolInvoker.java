package com.example.chat.util;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * OpenAI 兼容 function calling 调用器。
 *
 * <p>统一 ToolDispatcher / SubAgentWorker 中重复的：
 * <ul>
 *   <li>POST /chat/completions（带 tools 参数）；</li>
 *   <li>从响应提取 choices[0].message.tool_calls / content；</li>
 *   <li>解析 tool_call.function.arguments JSON 并执行工具（经 {@link LlmToolExecutor} 回调）。</li>
 * </ul>
 * </p>
 */
@Component
public class LlmToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(LlmToolInvoker.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public LlmToolInvoker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 LLM（带 tools 参数），返回完整响应 map（含 choices[0].message）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> callWithTools(ModelConfig config, String baseUrl, String apiKey,
                                             List<LLMMessage> messages, double temperature,
                                             List<Map<String, Object>> tools) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.model);
        requestBody.put("messages", LLMMessage.toMapList(messages));
        requestBody.put("temperature", temperature);
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
            throw new LLMCallException(response.statusCode(), "LLM API returned status " + response.statusCode());
        }
        return objectMapper.readValue(response.body(), Map.class);
    }

    /** 从 LLM 响应提取 choices[0].message.tool_calls */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> extractToolCalls(Map<String, Object> llmResp) {
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
            log.warn("[LlmToolInvoker] 解析 tool_calls 失败: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** 从 LLM 响应提取 choices[0].message.content */
    @SuppressWarnings("unchecked")
    public String extractContent(Map<String, Object> llmResp) {
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

    /** 取 tool_call 中的 function.name，缺失时回退 "tool" */
    @SuppressWarnings("unchecked")
    public String toolNameOf(Map<String, Object> toolCall) {
        if (toolCall == null) return "tool";
        Object fn = toolCall.get("function");
        if (fn instanceof Map) {
            Object name = ((Map<?, ?>) fn).get("name");
            if (name != null) return name.toString();
        }
        return "tool";
    }

    /**
     * 解析 tool_call.function.arguments 为参数 map。
     * arguments 可能是 JSON 字符串或已经是 map；格式错误时返回空 map。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseArguments(Map<String, Object> toolCall) {
        Object fn = toolCall != null ? toolCall.get("function") : null;
        if (!(fn instanceof Map)) return new LinkedHashMap<>();
        Object arguments = ((Map<?, ?>) fn).get("arguments");

        Map<String, Object> params = new LinkedHashMap<>();
        if (arguments instanceof String) {
            try {
                Object parsed = objectMapper.readValue((String) arguments, Object.class);
                if (parsed instanceof Map) {
                    params.putAll((Map<String, Object>) parsed);
                }
            } catch (Exception e) {
                log.warn("[LlmToolInvoker] 解析工具参数失败: {}", e.getMessage());
            }
        } else if (arguments instanceof Map) {
            params.putAll((Map<String, Object>) arguments);
        }
        return params;
    }

    /**
     * 执行单个 tool_call：解析参数 → 经 {@link LlmToolExecutor} 执行 → 返回结果字符串。
     */
    public String executeOneToolCall(Map<String, Object> toolCall, LlmToolExecutor executor) {
        String toolName = toolNameOf(toolCall);
        Map<String, Object> params = parseArguments(toolCall);
        try {
            String result = executor.execute(toolName, params);
            log.info("[LlmToolInvoker] 工具 {} 执行成功 resultLen={}", toolName,
                    result != null ? result.length() : 0);
            return result != null ? result : "";
        } catch (Exception e) {
            log.error("[LlmToolInvoker] 工具 {} 执行失败: {}", toolName, e.getMessage());
            return "[工具 " + toolName + " 执行失败: " + e.getMessage() + "]";
        }
    }

    /** 将 Map 消息列表转回 LLMMessage */
    public static List<LLMMessage> fromMapList(List<Map<String, Object>> maps) {
        List<LLMMessage> result = new ArrayList<>();
        for (Map<String, Object> m : maps) {
            result.add(LLMMessage.fromMap(m));
        }
        return result;
    }
}
