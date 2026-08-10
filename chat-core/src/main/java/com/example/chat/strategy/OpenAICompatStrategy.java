package com.example.chat.strategy;

import com.example.chat.dto.LLMMessage;
import com.example.chat.exception.LLMCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 API 调用策略（异步 NIO 版）
 * 适用于千问（Qwen）和 DeepSeek，使用 /chat/completions 接口
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.module.core", havingValue = "true", matchIfMissing = false)
public class OpenAICompatStrategy implements LLMStrategy {

    private static final Logger log = LoggerFactory.getLogger(OpenAICompatStrategy.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build();

    public OpenAICompatStrategy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String invoke(String baseUrl, String apiKey, String model,
                         List<LLMMessage> messages, double temperature) throws Exception {
        return invokeAsync(baseUrl, apiKey, model, messages, temperature).join();
    }

    @Override
    public CompletableFuture<String> invokeAsync(String baseUrl, String apiKey, String model,
                                                  List<LLMMessage> messages, double temperature) {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", LLMMessage.toMapList(messages));
        requestBody.put("temperature", temperature);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new LLMCallException(response.statusCode(), "LLM API returned status " + response.statusCode());
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(cleanJson(response.body()), Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            throw new LLMCallException("LLM API returned no choices");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return message != null ? message.get("content").toString() : "No response";
                    } catch (Exception e) {
                        String resp = response.body();
                        log.error("[OpenAICompat] JSON 解析失败 body前500字={} rootCause={}: {}",
                                resp != null ? resp.substring(0, Math.min(500, resp.length())) : "null",
                                e.getClass().getSimpleName(), e.getMessage());
                        throw new LLMCallException("LLM 响应解析失败", e);
                    }
                });
    }

    @Override
    public String invokeStream(String baseUrl, String apiKey, String model,
                               List<LLMMessage> messages, double temperature,
                               Consumer<String> callback) throws Exception {
        return invokeStreamAsync(baseUrl, apiKey, model, messages, temperature, callback).join();
    }

    @Override
    public CompletableFuture<String> invokeStreamAsync(String baseUrl, String apiKey, String model,
                                                        List<LLMMessage> messages, double temperature,
                                                        Consumer<String> callback) {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", LLMMessage.toMapList(messages));
        requestBody.put("temperature", temperature);
        requestBody.put("stream", true);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        StringBuilder errBody = new StringBuilder();
                        response.body().forEach(errBody::append);
                        throw new LLMCallException(response.statusCode(), "LLM API status " + response.statusCode());
                    }
                    StringBuilder fullAnswer = new StringBuilder();
                    response.body().forEach(line -> {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) return;
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> chunk = objectMapper.readValue(cleanJson(data), Map.class);
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                    if (delta != null) {
                                        Object content = delta.get("content");
                                        if (content != null && !content.toString().isEmpty()) {
                                            String token = content.toString();
                                            fullAnswer.append(token);
                                            if (callback != null) {
                                                callback.accept(token);
                                            }
                                        }
                                    }
                                }
                            } catch (Exception streamErr) {
                                log.debug("[OpenAICompat] 流式行解析失败: {}", streamErr.getMessage());
                            }
                        }
                    });
                    return fullAnswer.toString();
                });
    }

    /**
     * 清洗 LLM 返回的 JSON，移除非法控制字符（如 CTRL-CHAR code 31）
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
