package com.example.chat.service;

import com.example.chat.dto.LLMMessage;
import com.example.chat.entity.MediaGenRecord;
import com.example.chat.entity.ModelConfig;
import com.example.chat.exception.LLMCallException;
import com.example.chat.exception.MediaGenException;
import com.example.chat.repository.MediaGenRecordRepository;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.util.BaseUrlResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态生成核心服务 —— 从 MediaGenController 中提取全部业务逻辑。
 */
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.NPathComplexity", "PMD.NcssCount", "PMD.CognitiveComplexity"})
@Service
public class MediaGenService {

    private static final Logger log = LoggerFactory.getLogger(MediaGenService.class);

    static final long IMAGE_MODEL_ID = 4L;
    static final long VIDEO_MODEL_ID = 5L;
    static final long MODEL3D_MODEL_ID = 7L;
    static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(120);
    static final int VIDEO_POLL_INTERVAL_MS = 10000;
    static final int VIDEO_MAX_POLL_COUNT = 360;
    static final int MODEL3D_POLL_INTERVAL_MS = 10000;
    static final int MODEL3D_MAX_POLL_COUNT = 60;

    private final MediaGenRecordRepository recordRepo;
    private final ModelConfigRepository modelConfigRepo;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final BaseUrlResolver baseUrlResolver;
    private final OssService ossService;

    public MediaGenService(MediaGenRecordRepository recordRepo,
                           ModelConfigRepository modelConfigRepo,
                           ObjectMapper objectMapper,
                           BaseUrlResolver baseUrlResolver,
                           @Autowired OssService ossService) {
        this.recordRepo = recordRepo;
        this.modelConfigRepo = modelConfigRepo;
        this.objectMapper = objectMapper;
        this.baseUrlResolver = baseUrlResolver;
        this.ossService = ossService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** 执行媒体生成完整流程 */
    public MediaGenResult generate(String prompt, String type, Long userId) {
        long modelId = switch (type) {
            case "video" -> VIDEO_MODEL_ID;
            case "3d" -> MODEL3D_MODEL_ID;
            default -> IMAGE_MODEL_ID;
        };

        ModelConfig config = modelConfigRepo.findById(modelId);
        if (config == null) {
            throw new IllegalArgumentException(("3d".equals(type) ? "3D" : "图像") + "模型未配置");
        }
        if (config.apiKeyEncrypted == null || config.apiKeyEncrypted.isBlank()) {
            throw new IllegalArgumentException("模型 API Key 未配置");
        }

        String apiKey = config.apiKeyEncrypted;
        String baseUrl = baseUrlResolver.resolve(config, "https://dashscope.aliyuncs.com");

        // 创建 running 记录
        Long recordId = null;
        if (userId != null) {
            try {
                MediaGenRecord record = new MediaGenRecord();
                record.userId = userId;
                record.prompt = prompt;
                record.mediaType = type;
                record.model = config.model;
                record.status = "running";
                recordRepo.insert(record);
                recordId = record.id;
                log.info("[MediaGen] 创建running记录, id={}, userId={}, type={}", recordId, userId, type);
            } catch (DataAccessException ex) {
                log.warn("[MediaGen] 创建记录失败", ex);
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

            // 转存 OSS
            String ossUrl = ossService.transferToOss(mediaUrl, type);
            mediaUrl = ossUrl;

            String ossGlb = null;
            String ossObj = null;
            String ossPreview = null;
            if (extra3D != null) {
                ossGlb = ossService.transferToOss(extra3D.get("glb"), "3d");
                ossObj = ossService.transferToOss(extra3D.get("obj"), "3d");
                ossPreview = ossService.transferToOss(extra3D.get("preview"), "3d");
                extra3D.put("glb", ossGlb);
                extra3D.put("obj", ossObj);
                extra3D.put("preview", ossPreview);
                mediaUrl = ossGlb;
            }

            // 更新记录
            if (recordId != null) {
                try {
                    recordRepo.updateToDone(recordId, mediaUrl, ossGlb, ossObj, ossPreview);
                } catch (DataAccessException ex) {
                    log.warn("[MediaGen] 更新记录失败", ex);
                }
            }

            MediaGenResult result = new MediaGenResult(mediaUrl, type, config.model, recordId, extra3D);
            log.info("[MediaGen] 生成成功, id={}, model={}", recordId, config.model);
            return result;

        } catch (Exception e) {
            log.error("[MediaGen] 生成失败", e);
            if (recordId != null) {
                try {
                    recordRepo.updateToError(recordId, e.getMessage());
                } catch (DataAccessException ignored) { }
            }
            throw new MediaGenException(e.getMessage(), e);
        }
    }

    // ---- 图片生成 ----

    private String callImageGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String url = baseUrl.replaceAll("/+$", "") + "/api/v1/services/aigc/multimodal-generation/generation";
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("messages", List.of(LLMMessage.user(prompt).toMap())),
                "parameters", Map.of("size", "1024*1024", "n", 1));
        HttpResponse<String> resp = httpPost(url, apiKey, objectMapper.writeValueAsString(body), IMAGE_TIMEOUT);
        if (resp.statusCode() != 200)
            throw new LLMCallException("API返回状态 " + resp.statusCode() + ": " + resp.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resp.body(), Map.class);
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null)
            throw new MediaGenException(result.get("message") != null ? result.get("message").toString() : "未知错误");

        List<Map<String, Object>> choices = (List<Map<String, Object>>) output.get("choices");
        if (choices == null || choices.isEmpty()) throw new LLMCallException("API未返回结果");

        Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
        List<Map<String, Object>> content = (List<Map<String, Object>>) msg.get("content");
        if (content == null || content.isEmpty()) throw new LLMCallException("API未返回内容");

        Object imageUrl = content.get(0).get("image");
        return imageUrl != null ? imageUrl.toString() : "";
    }

    // ---- 视频生成 ----

    private String callVideoGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String submitUrl = baseUrl.replaceAll("/+$", "") + "/api/v1/services/aigc/video-generation/video-synthesis";
        Map<String, Object> body = Map.of(
                "model", model,
                "input", Map.of("prompt", prompt),
                "parameters", Map.of("resolution", "720P", "ratio", "16:9", "duration", 10));
        HttpResponse<String> resp = httpPost(submitUrl, apiKey, objectMapper.writeValueAsString(body),
                Duration.ofSeconds(30), "X-DashScope-Async", "enable");
        if (resp.statusCode() != 200)
            throw new LLMCallException("提交视频任务失败，状态 " + resp.statusCode() + ": " + resp.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resp.body(), Map.class);
        Map<String, Object> output = (Map<String, Object>) result.get("output");
        if (output == null)
            throw new MediaGenException(result.get("message") != null ? result.get("message").toString() : "未知错误");

        String taskId = (String) output.get("task_id");
        if (taskId == null || taskId.isBlank()) throw new LLMCallException("未返回 task_id");

        log.info("[MediaGen-Video] task_id={}", taskId);
        String pollUrl = baseUrl.replaceAll("/+$", "") + "/api/v1/tasks/" + taskId;
        for (int i = 0; i < VIDEO_MAX_POLL_COUNT; i++) {
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(VIDEO_POLL_INTERVAL_MS);
            HttpResponse<String> pr = httpGet(pollUrl, apiKey, Duration.ofSeconds(15));
            if (pr.statusCode() != 200) continue;

            Map<String, Object> prs = objectMapper.readValue(pr.body(), Map.class);
            Map<String, Object> po = (Map<String, Object>) prs.get("output");
            if (po == null) continue;
            String status = (String) po.get("task_status");

            if ("SUCCEEDED".equals(status)) {
                String videoUrl = (String) po.get("video_url");
                if (videoUrl != null && !videoUrl.isBlank()) return videoUrl;
                throw new LLMCallException("任务成功但未返回 video_url");
            } else if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                throw new MediaGenException(po.get("message") != null ? po.get("message").toString() : "任务失败");
            }
        }
        throw new LLMCallException("视频生成超时，已轮询 " + VIDEO_MAX_POLL_COUNT + " 次");
    }

    // ---- 3D 生成 ----

    private Map<String, String> call3DGeneration(String baseUrl, String apiKey, String model, String prompt) throws Exception {
        String submitUrl = baseUrl.replaceAll("/+$", "") + "/v1/api/3d/submit";
        String jsonBody = objectMapper.writeValueAsString(Map.of("model", model, "prompt", prompt));
        HttpResponse<String> sr = httpPost(submitUrl, apiKey, jsonBody, Duration.ofSeconds(30));
        if (sr.statusCode() != 200)
            throw new LLMCallException("3D任务提交失败，状态 " + sr.statusCode() + ": " + sr.body());

        @SuppressWarnings("unchecked")
        Map<String, Object> submitResult = objectMapper.readValue(sr.body(), Map.class);
        String taskId = (String) submitResult.get("id");
        if (taskId == null || taskId.isBlank()) {
            Object data = submitResult.get("data");
            if (data instanceof Map) taskId = (String) ((Map<?, ?>) data).get("id");
        }
        if (taskId == null || taskId.isBlank())
            throw new LLMCallException("3D任务提交未返回 id: " + sr.body());

        log.info("[MediaGen-3D] task_id={}", taskId);
        String queryUrl = baseUrl.replaceAll("/+$", "") + "/v1/api/3d/query";
        for (int i = 0; i < MODEL3D_MAX_POLL_COUNT; i++) {
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(MODEL3D_POLL_INTERVAL_MS);
            String qJson = objectMapper.writeValueAsString(Map.of("model", model, "id", taskId));
            HttpResponse<String> qr = httpPost(queryUrl, apiKey, qJson, Duration.ofSeconds(30));
            if (qr.statusCode() != 200) continue;

            Map<String, Object> qrs = objectMapper.readValue(qr.body(), Map.class);
            String status = (String) qrs.getOrDefault("status", qrs.get("task_status"));
            if (status == null) continue;

            if ("succeeded".equalsIgnoreCase(status) || "SUCCEEDED".equalsIgnoreCase(status)
                    || "success".equalsIgnoreCase(status) || "done".equalsIgnoreCase(status)
                    || "completed".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                Map<String, String> urls = extract3DModelUrls(qrs);
                if (urls != null && !urls.isEmpty()) return urls;
                throw new LLMCallException("3D任务成功但未返回模型URL");
            } else if ("failed".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)
                    || "error".equalsIgnoreCase(status)) {
                throw new MediaGenException(qrs.get("message") != null ? qrs.get("message").toString() : "3D生成失败");
            }
        }
        throw new LLMCallException("3D生成超时，已轮询 " + MODEL3D_MAX_POLL_COUNT + " 次");
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> extract3DModelUrls(Map<String, Object> result) {
        Map<String, String> urls = new HashMap<>();
        Object data = result.get("data");
        if (data instanceof List) {
            for (Object item : (List<?>) data) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    String type = (String) m.get("type");
                    String url = (String) m.get("url");
                    String preview = (String) m.get("preview_image_url");
                    if (url != null && !url.isBlank()) {
                        if ("glb".equalsIgnoreCase(type)) urls.put("glb", url);
                        else if ("obj".equalsIgnoreCase(type)) urls.put("obj", url);
                    }
                    if (preview != null && !preview.isBlank() && !urls.containsKey("preview"))
                        urls.put("preview", preview);
                }
            }
        }
        if (urls.isEmpty()) {
            for (String key : new String[]{"model_url", "url", "download_url", "glb_url"}) {
                Object val = result.get(key);
                if (val instanceof String && !((String) val).isBlank()) { urls.put("glb", (String) val); break; }
            }
        }
        return urls.isEmpty() ? null : urls;
    }

    // ---- HTTP 工助方法 ----

    private HttpResponse<String> httpPost(String url, String apiKey, String body, Duration timeout, String... extraHeaders) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < extraHeaders.length; i += 2)
            builder.header(extraHeaders[i], extraHeaders[i + 1]);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> httpGet(String url, String apiKey, Duration timeout) throws Exception {
        return httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(timeout)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 生成结果 DTO */
    public record MediaGenResult(String url, String type, String model, Long recordId, Map<String, String> extra3D) {}
}
