package com.example.chat.controller;

import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.dto.MediaGenerateRequest;
import com.example.chat.entity.MediaGenRecord;
import com.example.chat.security.AuthUtils;
import com.example.chat.repository.MediaGenRecordRepository;
import com.example.chat.service.MediaGenService;
import com.example.chat.service.MediaGenService.MediaGenResult;
import com.example.chat.service.OssService;
import com.example.chat.service.RateLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Tag(name = "多模态生成", description = "文生图、文生视频、图生视频、文生 3D、图生 3D")
@RestController
@RequestMapping("/api/v1/media")
public class MediaGenController {

    private static final Logger log = LoggerFactory.getLogger(MediaGenController.class);

    private final MediaGenService mediaGenService;
    private final MediaGenRecordRepository mediaGenRecordRepository;
    private final OssService ossService;
    private final RateLimitService rateLimitService;

    private static final Set<String> MODEL3D_WHITELIST = new HashSet<>(Arrays.asList("雪梨", "ysy0929"));

    public MediaGenController(MediaGenService mediaGenService,
                              MediaGenRecordRepository mediaGenRecordRepository,
                              OssService ossService,
                              RateLimitService rateLimitService) {
        this.mediaGenService = mediaGenService;
        this.mediaGenRecordRepository = mediaGenRecordRepository;
        this.ossService = ossService;
        this.rateLimitService = rateLimitService;
    }

    // ---- 生成 ----

    @Operation(summary = "生成媒体", description = "支持 text_to_image / text_to_video / image_to_video / text_to_3d / image_to_3d")
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody MediaGenerateRequest req) {
        String prompt = req.getPrompt();
        String type = req.getType() == null || req.getType().isBlank() ? "image" : req.getType();

        if ("3d".equals(type) && !is3DAllowed())
            return ResponseEntity.status(403).body(ApiResponse.error(ErrorCode.FORBIDDEN, "3D模型生成功能暂未开放，敬请期待"));

        Long userId = getCurrentUserId();
        if (userId == null) return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));

        // 用户级限流：媒体生成消耗费用，防止刷量
        if (!rateLimitService.isAllowed(userId)) {
            long waitSeconds = rateLimitService.getRemainingSeconds(userId);
            Map<String, Object> resp = new HashMap<>(ApiResponse.error(ErrorCode.RATE_LIMITED,
                    "操作过于频繁，请" + waitSeconds + "秒后再试"));
            resp.put("retry_after", waitSeconds);
            return ResponseEntity.status(429).body(resp);
        }
        try {
            MediaGenResult result = mediaGenService.generate(prompt, type, userId);
            Map<String, Object> resp = new HashMap<>(Map.of(
                    "url", result.url(), "type", result.type(),
                    "model", result.model(), "record_id", result.recordId()));
            if (result.extra3D() != null) resp.putAll(result.extra3D());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(500).body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "生成失败: " + e.getMessage()));
        }
    }

    // ---- 查询 ----

    @Operation(summary = "查询生成状态", description = "根据记录 ID 查询媒体生成进度")
    @GetMapping("/status/{id}")
    public ResponseEntity<?> getStatus(@PathVariable Long id) {
        Long userId = getCurrentUserId();
        if (userId == null) return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));

        MediaGenRecord r = mediaGenRecordRepository.findById(id);
        if (r == null || !r.userId.equals(userId))
            return ResponseEntity.status(404).body(ApiResponse.error(ErrorCode.NOT_FOUND, "记录不存在"));

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

    @Operation(summary = "生成历史", description = "查询当前用户的媒体生成历史，可按类型筛选")
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(required = false) String type,
                                        @RequestParam(defaultValue = "20") int limit) {
        Long userId = getCurrentUserId();
        if (userId == null) return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        int effectiveLimit = Math.min(limit, 100);

        List<MediaGenRecord> records = (type != null && !type.isBlank())
                ? mediaGenRecordRepository.findByUserIdAndType(userId, type, effectiveLimit)
                : mediaGenRecordRepository.findByUserIdOrderByCreatedAtDesc(userId, effectiveLimit);

        List<Map<String, Object>> result = new ArrayList<>();
        for (MediaGenRecord r : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.id);
            item.put("prompt", r.prompt);
            item.put("type", r.mediaType);
            item.put("status", r.status);
            item.put("model", r.model);
            item.put("createdAt", r.createdAt != null ? r.createdAt.toString() : null);
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
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "3D 功能权限检查", description = "检查当前用户是否有 3D 模型生成权限")
    @GetMapping("/3d-access")
    public ResponseEntity<?> check3DAccess() {
        String username = getCurrentUsername();
        boolean allowed = username != null && MODEL3D_WHITELIST.contains(username);
        return ResponseEntity.ok(Map.of("allowed", allowed, "username", username == null ? "" : username));
    }

    // ---- 辅助 ----

    private boolean is3DAllowed() {
        String username = AuthUtils.extractUsernameFromContext();
        return username != null && MODEL3D_WHITELIST.contains(username);
    }

    private String getCurrentUsername() {
        return AuthUtils.extractUsernameFromContext();
    }

    private Long getCurrentUserId() {
        return AuthUtils.extractUserIdFromContext();
    }
}
