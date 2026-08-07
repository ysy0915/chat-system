package com.example.chat.strategy;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容 API 调用策略
 * 适用于千问（Qwen）和 DeepSeek，使用 /chat/completions 接口
 */
@Component
public class OpenAICompatStrategy implements LLMStrategy {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build();

    @Override
    public String invoke(String baseUrl, String apiKey, String model,
                         List<Map<String, Object>> messages, double temperature) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);

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
            throw new RuntimeException("LLM API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("LLM API returned no choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return message != null ? message.get("content").toString() : "No response";
    }

    @Override
    public String invokeStream(String baseUrl, String apiKey, String model,
                               List<Map<String, Object>> messages, double temperature,
                               Consumer<String> callback) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("temperature", temperature);
        requestBody.put("stream", true);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

        if (response.statusCode() != 200) {
            StringBuilder errBody = new StringBuilder();
            response.body().forEach(errBody::append);
            throw new RuntimeException("LLM API status " + response.statusCode() + ": " + errBody);
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
                } catch (Exception ignored) {}
            }
        });

        return fullAnswer.toString();
    }
}
