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
 * 豆包模型调用策略
 * 使用火山引擎 /responses 接口（与其他模型的 /chat/completions 不同）
 * 豆包不支持流式，调用后模拟一次性推送
 */
@Component
public class DoubaoStrategy implements LLMStrategy {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(120))
            .build();

    @Override
    public String invoke(String baseUrl, String apiKey, String model,
                         List<Map<String, Object>> messages, double temperature) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/responses";

        // 提取最后一条 user 消息作为 prompt
        String prompt = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                prompt = messages.get(i).get("content").toString();
                break;
            }
        }

        LinkedHashMap<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", prompt);

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
            throw new RuntimeException("Doubao API returned status " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

        // 豆包 /responses 接口返回格式: { output: [ { content: [ { type: "output_text", text: "..." } ] } ] }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputList = (List<Map<String, Object>>) result.get("output");
        if (outputList != null && !outputList.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : outputList) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> contents = (List<Map<String, Object>>) item.get("content");
                if (contents != null) {
                    for (Map<String, Object> c : contents) {
                        if ("output_text".equals(c.get("type")) && c.get("text") != null) {
                            sb.append(c.get("text").toString());
                        }
                    }
                }
            }
            if (sb.length() > 0) return sb.toString();
        }

        // 兼容 choices 格式（标准 OpenAI 兼容）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices != null && !choices.isEmpty()) {
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            return message != null ? message.get("content").toString() : "No response";
        }
        return "No response";
    }

    /**
     * 豆包不支持流式，调用非流式后一次性回调
     */
    @Override
    public String invokeStream(String baseUrl, String apiKey, String model,
                               List<Map<String, Object>> messages, double temperature,
                               Consumer<String> callback) throws Exception {
        String answer = invoke(baseUrl, apiKey, model, messages, temperature);
        if (callback != null && !answer.isEmpty()) {
            callback.accept(answer);
        }
        return answer;
    }

    @Override
    public boolean supportsStream() {
        return false;  // 豆包不支持真正的流式
    }
}
