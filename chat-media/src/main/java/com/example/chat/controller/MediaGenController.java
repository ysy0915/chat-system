package com.example.chat.controller;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.ModelConfig;
import com.example.chat.entity.MediaGenRecord;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.repository.MediaGenRecordRepository;
import com.example.chat.service.OssService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/media")
public class MediaGenController {

    private static final Logger log = LoggerFactory.getLogger(MediaGenController.class);

    @Autowired
    private OssService ossService;

    @Autowired
    private MediaGenRecordRepository mediaGenRecordRepository;

    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private static final long IMAGE_MODEL_ID = 4L;
    private static final long VIDEO_MODEL_ID = 5L;
    private static final long MODEL3D_MODEL_ID = 7L;
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(120);
    private static final int VIDEO_POLL_INTERVAL_MS = 10000;
    private static final int VIDEO_MAX_POLL_COUNT = 360;
    private static final int MODEL3D_POLL_INTERVAL_MS = 10000;
    private static final int MODEL3D_MAX_POLL_COUNT = 60;

    // 3D 模型生成白名单用户（用户名）
    private static final Set<String> MODEL3D_WHITELIST = new HashSet<>(Arrays.asList("雪梨", "ysy0929"));

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

        long modelId;
        String typeLabel;
        switch (type) {
            case "video":
                modelId = VIDEO_MODEL_ID; typeLabel = "视频"; break;
            case "3d":
                modelId = MODEL3D_MODEL_ID; typeLabel = "3D模型"; break;
            default:
                modelId = IMAGE_MODEL_ID; typeLabel = "图像"; break;
        }

        // 3D 模型生成需要白名单权限
        if ("3d".equals(type)) {
            String currentUsername = getCurrentUsername();
            if (currentUsername == null || !MODEL3D_WHITELIST.contains(currentUsername)) {
                return ResponseEntity.status(403).body(Map.of("error", "3D模型生成功能暂未开放，敬请期待"));
            }
        }

        ModelConfig config = modelConfigRepository.findById(modelId);
        if (config == null) {
            return ResponseEntity.status(500).body(Map.of("error", typeLabel + "模型未配置"));
        }

        String apiKey = config.apiKeyEncrypted;
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(500).body(Map.of("error", typeLabel + "模型 API Key 未配置"));
        }

        String baseUrl = resolveBaseUrl(config);

        // 先创建 running 状态的记录
        Long currentUserId = getCurrentUserId();
        Long recordId = null;
        if (currentUserId != null) {
            try {
                MediaGenRecord record = new MediaGenRecord();
                record.userId = currentUserId;
                record.prompt = prompt;
                record.mediaType = type;
                record.model = config.model;
                record.status = "running";
                recordId = mediaGenRecordRepository.insert(record);
                log.info("[MediaGen] 创建running记录, id={}, userId={}, type={}", recordId, currentUserId, type);
            } catch (Exception ex) {
                log.warn("[MediaGen] 创建记录失败: {}", ex.getMessage());
            }
        }

        try {
            String mediaUrl;
            Map<String, String> extra3D = null;
            if ("3d".equals(type)) {
                extra3D = call3DGeneration(baseUrl, apiKey, config.model, prompt);
                mediaUrl = extra3D.get("glb");
            } else if ("video".equals(type)) {
                mediaUrl = callVideoGeneration(baseUrl, apiKey, config.model, prompt);
            } else {
                mediaUrl = callImageGeneration(baseUrl, apiKey, config.model, prompt);
            }

            // 转存到 OSS（确保 URL 永久有效）
            String ossUrl = ossService.transferToOss(mediaUrl, type);
            mediaUrl = ossUrl;

            // 3D 额外文件也转存
            String ossGlb = null, ossObj = null, ossPreview = null;
            if (extra3D != null) {
                ossGlb = ossService.transferToOss(extra3D.get("glb"), "3d");
                ossObj = ossService.transferToOss(extra3D.get("obj"), "3d");
                ossPreview = ossService.transferToOss(extra3D.get("preview"), "3d");
                extra3D.put("glb", ossGlb);
                extra3D.put("obj", ossObj);
                extra3D.put("preview", ossPreview);
                mediaUrl = ossGlb;
            }

            // 更新记录为 done
            if (recordId != null) {
                try {
                    mediaGenRecordRepository.updateToDone(recordId, mediaUrl,
                            ossGlb, ossObj, ossPreview);
                    log.info("[MediaGen] 记录更新为done, id={}", recordId);
                } catch (Exception ex) {
                    log.warn("[MediaGen] 更新记录失败: {}", ex.getMessage());
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("url", mediaUrl);
            response.put("type", type);
            response.put("model", config.model);
            response.put("record_id", recordId);
            if (extra3D != null) response.putAll(extra3D);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[ERROR] MediaGen: {}", e.getMessage(), e);
            // 更新记录为 error
            if (recordId != null) {
                try {
                    mediaGenRecordRepository.updateToError(recordId, e.getMessage());
                } catch (Exception ignored) {}
            }
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
                                LLMMessage.user(prompt).toMap()
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

    private Map<String, String> call3DGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        // Step 1: 提交 3D 生成任务
        String submitUrl = baseUrl.replaceAll("/+$", "") + "/v1/api/3d/submit";
        Map<String, Object> submitBody = Map.of(
                "model", model,
                "prompt", prompt
        );
        String jsonBody = objectMapper.writeValueAsString(submitBody);

        HttpRequest submitRequest = HttpRequest.newBuilder()
                .uri(URI.create(submitUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> submitResponse = httpClient.send(submitRequest, HttpResponse.BodyHandlers.ofString());
        log.info("[MediaGen-3D] 提交任务, status={}, bodyLen={}", submitResponse.statusCode(), submitResponse.body().length());

        if (submitResponse.statusCode() != 200) {
            throw new RuntimeException("3D任务提交失败，状态 " + submitResponse.statusCode() + ": " + submitResponse.body());
        }

        Map<String, Object> submitResult = objectMapper.readValue(submitResponse.body(), Map.class);
        String taskId = (String) submitResult.get("id");
        if (taskId == null || taskId.isBlank()) {
            // 尝试从 data 字段提取
            Object data = submitResult.get("data");
            if (data instanceof Map) {
                taskId = (String) ((Map<?, ?>) data).get("id");
            }
        }
        if (taskId == null || taskId.isBlank()) {
            throw new RuntimeException("3D任务提交未返回 id: " + submitResponse.body());
        }

        log.info("[MediaGen-3D] 任务已提交, id={}", taskId);

        // Step 2: 轮询查询任务状态
        String queryUrl = baseUrl.replaceAll("/+$", "") + "/v1/api/3d/query";
        for (int i = 0; i < MODEL3D_MAX_POLL_COUNT; i++) {
            Thread.sleep(MODEL3D_POLL_INTERVAL_MS);

            Map<String, Object> queryBody = Map.of(
                    "model", model,
                    "id", taskId
            );
            String queryJson = objectMapper.writeValueAsString(queryBody);

            HttpRequest queryRequest = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson))
                    .build();

            HttpResponse<String> queryResponse = httpClient.send(queryRequest, HttpResponse.BodyHandlers.ofString());
            if (queryResponse.statusCode() != 200) {
                log.warn("[MediaGen-3D] 轮询失败, status={}", queryResponse.statusCode());
                continue;
            }

            Map<String, Object> queryResult = objectMapper.readValue(queryResponse.body(), Map.class);
            String status = (String) queryResult.get("status");
            if (status == null) status = (String) queryResult.get("task_status");
            log.info("[MediaGen-3D] 轮询 status={} (第{}次)", status, (i + 1));

            if ("succeeded".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)
                    || "success".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status)
                    || "completed".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                // 提取模型 URL
                log.info("[MediaGen-3D] 任务完成, 完整返回: {}", queryResponse.body());
                Map<String, String> urls = extract3DModelUrls(queryResult);
                if (urls != null && !urls.isEmpty()) {
                    return urls;
                }
                throw new RuntimeException("3D任务成功但未返回模型URL: " + queryResponse.body());
            } else if ("failed".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                    || "error".equalsIgnoreCase(status)) {
                String msg = queryResult.get("message") != null ? queryResult.get("message").toString() : "3D生成失败";
                throw new RuntimeException(msg);
            }
        }

        throw new RuntimeException("3D生成超时，已轮询 " + MODEL3D_MAX_POLL_COUNT + " 次");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extract3DModelUrls(Map<String, Object> result) {
        // 腾讯 3D API 返回格式: { status, data: [ {type:"obj", url, preview_image_url}, {type:"glb", url, preview_image_url} ] }
        Map<String, String> urls = new HashMap<>();
        Object data = result.get("data");
        if (data instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) data;
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<String, Object> itemMap = (Map<String, Object>) item;
                    String itemType = (String) itemMap.get("type");
                    String itemUrl = (String) itemMap.get("url");
                    String previewUrl = (String) itemMap.get("preview_image_url");
                    if (itemUrl != null && !itemUrl.isBlank()) {
                        if ("glb".equalsIgnoreCase(itemType)) {
                            urls.put("glb", itemUrl);
                        } else if ("obj".equalsIgnoreCase(itemType)) {
                            urls.put("obj", itemUrl);
                        }
                    }
                    if (previewUrl != null && !previewUrl.isBlank() && !urls.containsKey("preview")) {
                        urls.put("preview", previewUrl);
                    }
                }
            }
        }
        // 兼容其他可能的结构
        if (urls.isEmpty()) {
            String[] urlKeys = {"model_url", "url", "download_url", "glb_url"};
            for (String key : urlKeys) {
                Object val = result.get(key);
                if (val instanceof String && !((String) val).isBlank()) {
                    urls.put("glb", (String) val);
                    break;
                }
            }
        }
        return urls.isEmpty() ? null : urls;
    }

    /**
     * 获取当前登录用户名
     * JWT subject 是 email（如 "雪梨@chat.local"），这里提取 @ 前面的 name 部分
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof org.springframework.security.core.userdetails.User) {
            String subject = ((org.springframework.security.core.userdetails.User) auth.getPrincipal()).getUsername();
            if (subject != null && subject.contains("@")) {
                return subject.substring(0, subject.indexOf("@"));
            }
            return subject;
        }
        return null;
    }

    /**
     * 获取当前登录用户ID
     * JwtAuthenticationFilter 把 uid 存在 Authentication.credentials 中
     */
    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getCredentials() instanceof Long) {
            return (Long) auth.getCredentials();
        }
        if (auth != null && auth.getCredentials() instanceof Number) {
            return ((Number) auth.getCredentials()).longValue();
        }
        return null;
    }

    /**
     * 查询单条记录状态（前端轮询 running 记录用）
     * GET /api/v1/media/status/{id}
     */
    @GetMapping("/status/{id}")
    public ResponseEntity<?> getStatus(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        MediaGenRecord r = mediaGenRecordRepository.findById(id);
        if (r == null || !r.userId.equals(userId)) {
            return ResponseEntity.status(404).body(Map.of("error", "记录不存在"));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", r.id);
        result.put("status", r.status);
        result.put("type", r.mediaType);
        result.put("prompt", r.prompt);
        if ("done".equals(r.status) && r.mediaUrl != null) {
            result.put("url", ossService.refreshSignedUrl(r.mediaUrl));
            if (r.glbUrl != null) result.put("glb", ossService.refreshSignedUrl(r.glbUrl));
            if (r.objUrl != null) result.put("obj", ossService.refreshSignedUrl(r.objUrl));
            if (r.previewUrl != null) result.put("preview", ossService.refreshSignedUrl(r.previewUrl));
        } else {
            result.put("url", r.mediaUrl);
            result.put("glb", r.glbUrl);
            result.put("obj", r.objUrl);
            result.put("preview", r.previewUrl);
        }
        result.put("error", r.errorMsg);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询当前用户的媒体生成历史
     * GET /api/v1/media/history?type=image&limit=20
     */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(required = false) String type,
                                         @RequestParam(defaultValue = "20") int limit) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        if (limit > 100) limit = 100;
        List<MediaGenRecord> records;
        if (type != null && !type.isBlank()) {
            records = mediaGenRecordRepository.findByUserIdAndType(userId, type, limit);
        } else {
            records = mediaGenRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, limit);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (MediaGenRecord r : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.id);
            item.put("prompt", r.prompt);
            item.put("type", r.mediaType);
            item.put("status", r.status);
            // done 状态的记录，动态生成签名 URL
            if ("done".equals(r.status) && r.mediaUrl != null) {
                item.put("url", ossService.refreshSignedUrl(r.mediaUrl));
                if (r.glbUrl != null) item.put("glb", ossService.refreshSignedUrl(r.glbUrl));
                if (r.objUrl != null) item.put("obj", ossService.refreshSignedUrl(r.objUrl));
                if (r.previewUrl != null) item.put("preview", ossService.refreshSignedUrl(r.previewUrl));
            } else {
                item.put("url", r.mediaUrl);
                item.put("glb", r.glbUrl);
                item.put("obj", r.objUrl);
                item.put("preview", r.previewUrl);
            }
            item.put("model", r.model);
            item.put("createdAt", r.createdAt != null ? r.createdAt.toString() : null);
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 检查当前用户是否有 3D 模型生成权限
     */
    @GetMapping("/3d-access")
    public ResponseEntity<?> check3DAccess() {
        String username = getCurrentUsername();
        boolean allowed = username != null && MODEL3D_WHITELIST.contains(username);
        return ResponseEntity.ok(Map.of("allowed", allowed, "username", username == null ? "" : username));
    }

    private String resolveBaseUrl(ModelConfig config) {
        if (config.metaJson != null && !config.metaJson.isBlank()) {
            try {
                Map<String, Object> meta = objectMapper.readValue(config.metaJson, Map.class);
                // 兼容驼峰和下划线两种 key
                Object baseUrl = meta.get("baseUrl");
                if (baseUrl == null) baseUrl = meta.get("base_url");
                if (baseUrl != null) return baseUrl.toString();
            } catch (Exception ignored) {}
        }
        return "https://dashscope.aliyuncs.com";
    }
}
