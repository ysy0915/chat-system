package com.example.chat.controller;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
public class MediaGenController {

    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final long IMAGE_MODEL_ID = 4L;
    private static final long VIDEO_MODEL_ID = 5L;
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration VIDEO_TIMEOUT = Duration.ofSeconds(300);

    public MediaGenController(ModelConfigRepository modelConfigRepository, ObjectMapper objectMapper) {
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody Map<String, String> payload) {
        String prompt = payload.get("prompt");
        String type = payload.getOrDefault("type", "image");

        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入描述内容"));
        }

        long modelId = "video".equals(type) ? VIDEO_MODEL_ID : IMAGE_MODEL_ID;
        String typeLabel = "video".equals(type) ? "视频" : "图像";

        ModelConfig config = modelConfigRepository.findById(modelId);
        if (config == null) {
            return ResponseEntity.status(500).body(Map.of("error", typeLabel + "模型未配置"));
        }

        String apiKey = config.apiKeyEncrypted;
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of("error", typeLabel + "模型 API Key 未配置"));
        }

        String baseUrl = resolveBaseUrl(config);

        try {
            String mediaUrl = callMediaGeneration(baseUrl, apiKey, config.model, prompt, type);
            return ResponseEntity.ok(Map.of(
                    "url", mediaUrl,
                    "type", type,
                    "model", config.model
            ));
        } catch (Exception e) {
            System.err.println("[ERROR] MediaGen: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "生成失败: " + e.getMessage()));
        }
    }

    private String callMediaGeneration(String baseUrl, String apiKey, String model, String prompt, String type) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/api/v1/services/aigc/multimodal-generation/generation";
        Duration timeout = "video".equals(type) ? VIDEO_TIMEOUT : IMAGE_TIMEOUT;

        Map<String, Object> requestBody = Map.of(
                "model", model,
                "input", Map.of(
                        "messages", List.of(
                                Map.of("role", "user", "content", List.of(Map.of("text", prompt)))
                        )
                ),
                "parameters", Map.of(
                        "size", "1024*1024",
                        "n", 1
                )
        );

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("API返回状态 " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null) {
            String errMsg = result.get("message") != null ? result.get("message").toString() : "未知错误";
            throw new RuntimeException(errMsg);
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("API未返回结果");
        }

        Map<String, Object> firstChoice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) firstChoice.get("message");
        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("API未返回内容");
        }

        Map<String, Object> firstContent = content.get(0);
        Object mediaUrl = firstContent.getOrDefault("video", firstContent.get("image"));
        return mediaUrl != null ? mediaUrl.toString() : "";
    }

    private String resolveBaseUrl(ModelConfig config) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(config.metaJson, Map.class);
                Object baseUrl = meta.get("base_url");
                if (baseUrl != null) return baseUrl.toString();
            } catch (Exception ignored) {}
        }
        return "https://dashscope.aliyuncs.com";
    }
}
