package com.example.chat.strategy;

import com.example.chat.dto.LLMMessage;
import com.example.chat.exception.LLMCallException;
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
                .header("Accept-Encoding", "gzip")
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
                        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                        if (choices == null || choices.isEmpty()) {
                            throw new LLMCallException("LLM API returned no choices");
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        return message != null ? message.get("content").toString() : "No response";
                    } catch (Exception e) {
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
                                Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
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
                                // 流式解析行失败，跳过
                            }
                        }
                    });
                    return fullAnswer.toString();
                });
    }
}
