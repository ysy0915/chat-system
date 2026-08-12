package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.exception.LLMCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 直接 HTTP 调用客户端。
 * LLMInvoker 不可用时（未注入 / 熔断），服务可降级使用此类直接调用 LLM API。
 *
 * 统一了 KnowledgeGraphService / ModelAutoChatService 中重复的降级 HTTP 逻辑。
 */
@Service
public class DirectLLMClient {

    private static final Logger log = LoggerFactory.getLogger(DirectLLMClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DirectLLMClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 直接 HTTP 调用 /chat/completions 并返回 content。
     *
     * @param baseUrl     LLM API 地址（不含路径尾部斜杠）
     * @param apiKey      API Key
     * @param model       模型名
     * @param messages    消息列表
     * @param temperature 温度
     * @param maxTokens   最大 token 数（-1 表示不设）
     * @return choices[0].message.content
     * @throws LLMCallException 调用失败时抛出
     */
    @SuppressWarnings("unchecked")
    public String call(String baseUrl, String apiKey, String model,
                       List<LLMMessage> messages, double temperature, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LLMCallException(model, "API Key 为空，无法调用 LLM", null);
        }

        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", LLMMessage.toMapList(messages));
        body.put("temperature", temperature);
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }

        String responseBody = null;
        try {
            String jsonBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("[DirectLLMClient] model={} status={} bodyLen={}", model,
                        response.statusCode(), response.body() != null ? response.body().length() : 0);
                throw new LLMCallException(response.statusCode(),
                        "LLM API 返回 " + response.statusCode() + ": " + truncateBody(response.body()));
            }

            responseBody = cleanJson(response.body());
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LLMCallException(model, "LLM API 返回空 choices", null);
            }

            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return "";
            }
            return unwrapNestedContent(message.get("content"));

        } catch (LLMCallException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DirectLLMClient] 调用失败 model={} rootCause={}: {} body前500字={}",
                    model, e.getClass().getSimpleName(), e.getMessage(),
                    responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null");
            throw new LLMCallException(model, "LLM 直接调用失败: " + e.getMessage(), e);
        }
    }

    private static String truncateBody(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    /**
     * 双嵌套防御：部分第三方中转网关会把完整 chat.completion 响应体序列化后
     * 放入 {@code message.content} 字段（content 里再包一层 choices/message/content）。
     * 检测到该形态时递归提取最内层 content，普通文本原样返回，最多解包 3 层。
     */
    @SuppressWarnings("unchecked")
    private String unwrapNestedContent(Object content) {
        String current = content.toString();
        for (int depth = 0; depth < 3; depth++) {
            String trimmed = current.trim();
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return current;
            try {
                Map<String, Object> map = objectMapper.readValue(trimmed, Map.class);
                Object choicesObj = map.get("choices");
                if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return current;
                Object messageObj = ((Map<String, Object>) choices.get(0)).get("message");
                if (!(messageObj instanceof Map<?, ?> message)) return current;
                Object inner = message.get("content");
                if (inner == null || inner.toString().isBlank()) return current;
                // 若内层仍是响应体形态则继续解包，否则返回
                String innerStr = inner.toString().trim();
                if (innerStr.startsWith("{") && innerStr.contains("\"choices\"")) {
                    current = innerStr;
                    continue;
                }
                return innerStr;
            } catch (Exception e) {
                return current;
            }
        }
        return current;
    }

    /**
     * 清理 LLM 返回 JSON 中的非法控制字符，防止 Jackson 解析失败
     */
    private static String cleanJson(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
