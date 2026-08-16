package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.common.ApiResponse;
import com.example.chat.common.ErrorCode;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.User;
import com.example.chat.security.AuthUtils;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ContentSafetyService;
import com.example.chat.service.OnlineCountRedisService;
import com.example.chat.service.RateLimitService;
import com.example.chat.config.WebSocketSessionTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Tag(name = "消息管理", description = "消息发送、查询、重新生成、流式停止（核心入口）")
@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private static final Logger log = LoggerFactory.getLogger(MessageController.class);
    private final CoreClient coreClient;
    private final WebSocketSessionTracker sessionTracker;
    private final RateLimitService rateLimitService;
    private final ContentSafetyService contentSafetyService;
    private final BroadcastService broadcastService;
    private final OnlineCountRedisService onlineCountRedisService;
    private final ObjectMapper objectMapper;

    public MessageController(CoreClient coreClient,
                             WebSocketSessionTracker sessionTracker,
                             RateLimitService rateLimitService,
                             ContentSafetyService contentSafetyService,
                             BroadcastService broadcastService,
                             OnlineCountRedisService onlineCountRedisService,
                             ObjectMapper objectMapper) {
        this.coreClient = coreClient;
        this.sessionTracker = sessionTracker;
        this.rateLimitService = rateLimitService;
        this.contentSafetyService = contentSafetyService;
        this.broadcastService = broadcastService;
        this.onlineCountRedisService = onlineCountRedisService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "发送消息", description = "创建新消息并触发 AI 回答（速率限制 + 内容安全检测）")
    @PostMapping
    @SuppressWarnings("PMD.NPathComplexity") // 消息入口流水线：鉴权/限流/内容安全/落库/AI触发多分支，拆分破坏防御顺序
    public Callable<ResponseEntity<?>> createMessage(@RequestBody Map<String, Object> body) {
        // 返回 Callable：内容安全检测（阿里云 API，数百 ms）在独立异步线程池执行，
        // Tomcat 工作线程立即释放，避免高并发下 Tomcat 线程池被「等待检测」占满导致排队。
        // 检测无超时、fail-close：无论多慢都必须拿到结果，异常/挂掉则整条消息不能发出（安全第一）。
        //
        // 关键：SecurityContext 是 ThreadLocal，不会自动传递到 async 线程，
        // 必须在主线程（进入异步前）提取 userId，通过闭包传给异步方法。
        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return () -> ResponseEntity.status(401)
                    .body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return () -> doCreateMessage(body, userId);
    }

    /**
     * 消息创建流水线（在异步线程池执行）。
     * 内容安全检测为 fail-close 闸门：检测命中/异常均拦截，消息不落库、不触发 AI。
     *
     * @param userId 主线程已提取的用户 ID（不依赖 async 线程的 SecurityContext）
     */
    private ResponseEntity<?> doCreateMessage(Map<String, Object> body, Long userId) {
        Object reqIdObj = body.get("req_id");
        String reqId = (reqIdObj == null || "null".equals(String.valueOf(reqIdObj)) || String.valueOf(reqIdObj).isBlank())
                ? UUID.randomUUID().toString()
                : String.valueOf(reqIdObj);

        Object questionObj = body.get("question");
        String question = questionObj == null ? "" : questionObj.toString();

        boolean isPrivate = "true".equals(String.valueOf(body.get("private")));

        if (!rateLimitService.isAllowed(userId)) {
            long retryAfter = rateLimitService.getRemainingSeconds(userId);
            return ResponseEntity.status(429).body(rateLimitedResponse(retryAfter));
        }

        String safetyResult = contentSafetyService.detectSensitive(question);
        if (safetyResult != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST,
                    contentSafetyService.getLabelHint(safetyResult)));
        }

        User user = resolveOrCreateMessageUser(userId);
        userId = user.id;
        String userName = user != null && user.nickname != null && !user.nickname.isBlank() ? user.nickname : "用户" + userId;

        // 通过 CoreClient 插入消息
        Map<String, Object> msgPayload = new HashMap<>();
        msgPayload.put("reqId", reqId);
        msgPayload.put("userId", userId);
        msgPayload.put("question", question);
        msgPayload.put("status", "queued");
        msgPayload.put("isPrivate", isPrivate ? 1 : 0);
        coreClient.insertMessage(msgPayload);

        if (!isPrivate) {
            Map<String, Object> broadcastPayload = new HashMap<>();
            broadcastPayload.put("user_id", userId);
            broadcastPayload.put("user_name", userName);
            broadcastPayload.put("question", question);
            broadcastPayload.put("req_id", reqId);
            broadcastService.broadcast("/topic/public-questions", broadcastPayload);
        }

        Object preferred = body.get("preferred_model_config_id");
        boolean aiAnswer = "true".equals(String.valueOf(body.get("ai_answer")));
        if (aiAnswer) {
            Map<String, Object> messagePayload = new HashMap<>();
            messagePayload.put("req_id", reqId);
            messagePayload.put("user_id", userId);
            messagePayload.put("question", question);
            messagePayload.put("private", String.valueOf(isPrivate));
            if (preferred != null) {
                messagePayload.put("preferred_model_config_id", preferred);
            }
            // 深度思考开关透传（前端「深度思考」按钮，仅对豆包等原生思考模型生效）
            boolean deepThinking = "true".equals(String.valueOf(body.get("deep_thinking")));
            messagePayload.put("deep_thinking", String.valueOf(deepThinking));
            coreClient.chatProcess(messagePayload);
        }

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "queued", "user_id", userId));
    }

    @Operation(summary = "发送带文件消息", description = "上传文件并附带文本问题一起提问")
    @PostMapping("/with-file")
    public ResponseEntity<?> createMessageWithFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question,
            @RequestParam("req_id") String reqId) {

        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "文件不能为空"));
        }

        if (!rateLimitService.isAllowed(userId)) {
            long retryAfter = rateLimitService.getRemainingSeconds(userId);
            return ResponseEntity.status(429).body(rateLimitedResponse(retryAfter));
        }

        String safetyResult = contentSafetyService.detectSensitive(question);
        if (safetyResult != null) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST,
                    contentSafetyService.getLabelHint(safetyResult)));
        }

        User user = resolveOrCreateMessageUser(userId);
        userId = user.id;

        try {
            coreClient.chatProcessWithFile(reqId, userId, question,
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
        } catch (IOException ex) {
            log.error("processWithFile failed: {}", ex.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("文件处理失败: " + ex.getMessage()).withReqId(reqId).toMap());
        }

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "queued", "user_id", userId));
    }

    @SuppressWarnings("unchecked")
    private User resolveOrCreateMessageUser(Long rawUserId) {
        long userId = rawUserId != null ? rawUserId : 0L;

        Object result = coreClient.getUserById(userId);
        if (result != null) {
            return objectMapper.convertValue(result, User.class);
        }

        String guestEmail = "user_" + userId + "@chat.local";
        Object emailResult = coreClient.getUserByEmail(guestEmail);
        if (emailResult != null) {
            return objectMapper.convertValue(emailResult, User.class);
        }

        Map<String, Object> newUser = new HashMap<>();
        newUser.put("email", guestEmail);
        newUser.put("name", "");
        newUser.put("guestName", "用户" + userId);
        newUser.put("role", "user");
        newUser.put("passwordHash", "");
        coreClient.insertUser(newUser);

        Object created = coreClient.getUserByEmail(guestEmail);
        return objectMapper.convertValue(created, User.class);
    }

    @Operation(summary = "消息列表（当前用户）", description = "查询当前登录用户的消息历史")
    @GetMapping
    public ResponseEntity<?> listMessages() {
        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return ResponseEntity.ok(coreClient.listMessages(userId));
    }

    @Operation(summary = "最近私聊消息", description = "获取用户最近的私聊消息列表")
    @GetMapping("/recent")
    public ResponseEntity<?> listRecentPrivate() {
        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return ResponseEntity.ok(coreClient.listRecentPrivate(userId));
    }

    @Operation(summary = "搜索私聊消息", description = "按关键词搜索用户私聊消息，支持分页")
    @GetMapping("/search")
    public ResponseEntity<?> searchPrivateMessages(@RequestParam("keyword") String keyword,
                                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                                    @RequestParam(value = "size", defaultValue = "5") int size) {
        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return ResponseEntity.ok(coreClient.searchPrivateMessages(userId, keyword, page, size));
    }

    @Operation(summary = "上下文消息", description = "获取某条消息的对话上下文")
    @GetMapping("/context")
    public ResponseEntity<?> getContextMessages(@RequestParam("msg_id") Long msgId) {
        Long userId = AuthUtils.extractUserIdFromContext();
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }
        return ResponseEntity.ok(coreClient.getContextMessages(userId, msgId));
    }

    @Operation(summary = "在线人数", description = "查询当前页面的在线用户数")
    @GetMapping("/online-count")
    public ResponseEntity<?> getOnlineCount(@RequestParam(value = "page", defaultValue = "global") String page) {
        int hourlyActive = onlineCountRedisService.getHourlyActiveCount();
        return ResponseEntity.ok(Map.of("count", sessionTracker.getCount(page), "hourlyActive", hourlyActive));
    }

    @Operation(summary = "重新生成回答", description = "根据 req_id 和 user_id 触发 AI 重新生成")
    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        Long userId = AuthUtils.extractUserIdFromContext();

        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "req_id 不能为空"));
        }
        if (userId == null) {
            return ResponseEntity.status(401).body(ApiResponse.error(ErrorCode.UNAUTHORIZED, "未登录"));
        }

        try {
            coreClient.chatRegenerate(reqId, userId);
            return ResponseEntity.accepted().body(Map.of("status", "regenerating", "old_req_id", reqId));
        } catch (Exception ex) {
            log.error("[ERROR] regenerate 失败 reqId={}: {}", reqId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, "重新生成失败: " + ex.getMessage()));
        }
    }

    @Operation(summary = "停止生成", description = "停止指定 req_id 的 AI 生成流程")
    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST, "req_id 不能为空"));
        }
        coreClient.chatStop(reqId);
        log.info("[INFO] 收到停止请求 reqId={}", reqId);
        return ResponseEntity.ok(Map.of("status", "stopped", "req_id", reqId));
    }

    /** 429 限流响应：统一 ok/code/error + retry_after 附加字段 */
    private Map<String, Object> rateLimitedResponse(long retryAfter) {
        Map<String, Object> resp = new HashMap<>(ApiResponse.error(ErrorCode.RATE_LIMITED,
                "请求过于频繁，请 " + retryAfter + " 秒后再试"));
        resp.put("retry_after", retryAfter);
        return resp;
    }

    @MessageMapping("/online.register")
    public void handleOnlineRegister(@Header("simpSessionId") String sessionId, Map<String, String> payload) {
        String userId = payload.get("userId");
        String page = payload.get("page");
        if (userId != null) {
            sessionTracker.registerUser(sessionId, userId, "用户" + userId, page);
        }
    }

    @MessageMapping("/online.unregister")
    public void handleOnlineUnregister(@Header("simpSessionId") String sessionId, Map<String, String> payload) {
        String page = payload.get("page");
        sessionTracker.unregisterUser(sessionId, page);
    }
}
