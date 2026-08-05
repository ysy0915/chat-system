package com.example.chat.controller;

import com.example.chat.entity.ModelConfig;
import com.example.chat.repository.ModelConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
public class MediaGenController {

    private static final Logger log = LoggerFactory.getLogger(MediaGenController.class);

    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final long IMAGE_MODEL_ID = 4L;
    private static final long VIDEO_MODEL_ID = 5L;
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final int VIDEO_POLL_INTERVAL_MS = 10000;
    private static final int VIDEO_MAX_POLL_COUNT = 30;

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
            String mediaUrl;
            if ("video".equals(type)) {
                mediaUrl = callVideoGeneration(baseUrl, apiKey, config.model, prompt);
            } else {
                mediaUrl = callImageGeneration(baseUrl, apiKey, config.model, prompt);
            }
            return ResponseEntity.ok(Map.of(
                    "url", mediaUrl,
                    "type", type,
                    "model", config.model
            ));
        } catch (Exception e) {
            log.error("[ERROR] MediaGen: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "生成失败: " + e.getMessage()));
        }
    }

    private String callVideoGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String submitUrl = baseUrl.replaceAll("/+$", "") + "/api/v1/services/aigc/video-generation/video-synthesis";

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", Map.of("prompt", prompt));
        requestBody.put("parameters", Map.of(
                "resolution", "720P",
                "ratio", "16:9",
                "duration", 10
        ));

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(submitUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-Async", "enable")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("提交视频任务失败，状态 " + response.statusCode() + ": " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null) {
            String errMsg = result.get("message") != null ? result.get("message").toString() : "未知错误";
            throw new RuntimeException(errMsg);
        }

        String taskId = (String) output.get("task_id");
        if (taskId == null || taskId.isBlank()) {
            throw new RuntimeException("未返回 task_id");
        }

        log.info("[MediaGen] 视频任务已提交, task_id={}", taskId);

        String pollUrl = baseUrl.replaceAll("/+$", "") + "/api/v1/tasks/" + taskId;
        for (int i = 0; i < VIDEO_MAX_POLL_COUNT; i++) {
            Thread.sleep(VIDEO_POLL_INTERVAL_MS);

            HttpRequest pollRequest = HttpRequest.newBuilder()
                    .uri(URI.create(pollUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> pollResponse = httpClient.send(pollRequest, HttpResponse.BodyHandlers.ofString());
            if (pollResponse.statusCode() != 200) {
                log.warn("[MediaGen] 轮询失败, status={}", pollResponse.statusCode());
                continue;
            }

            Map<String, Object> pollResult = objectMapper.readValue(pollResponse.body(), Map.class);
            Map<String, Object> pollOutput = (Map<String, Object>) pollResult.get("output");
            if (pollOutput == null) continue;

            String status = (String) pollOutput.get("task_status");
            log.info("[MediaGen] 轮询 task_status={} (第{}次)", status, (i + 1));

            if ("SUCCEEDED".equals(status)) {
                String videoUrl = (String) pollOutput.get("video_url");
                if (videoUrl != null && !videoUrl.isBlank()) {
                    return videoUrl;
                }
                throw new RuntimeException("任务成功但未返回 video_url");
            } else if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                String msg = pollOutput.get("message") != null ? pollOutput.get("message").toString() : "任务失败";
                throw new RuntimeException(msg);
            }
        }

        throw new RuntimeException("视频生成超时，已轮询 " + VIDEO_MAX_POLL_COUNT + " 次");
    }

    private String callImageGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/api/v1/services/aigc/multimodal-generation/generation";

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
                .timeout(IMAGE_TIMEOUT)
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
        Object imageUrl = firstContent.get("image");
        return imageUrl != null ? imageUrl.toString() : "";
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
