package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import com.example.chat.dto.WsMessage;
import com.example.chat.entity.User;
import com.example.chat.service.BroadcastService;
import com.example.chat.service.ContentSafetyService;
import com.example.chat.service.RateLimitService;
import com.example.chat.config.WebSocketSessionTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private static final Logger log = LoggerFactory.getLogger(MessageController.class);
    private final CoreClient coreClient;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final WebSocketSessionTracker sessionTracker;
    private final RateLimitService rateLimitService;
    private final ContentSafetyService contentSafetyService;
    private final BroadcastService broadcastService;
    private final ObjectMapper objectMapper;

    public MessageController(CoreClient coreClient,
                             SimpMessagingTemplate simpMessagingTemplate,
                             WebSocketSessionTracker sessionTracker,
                             RateLimitService rateLimitService,
                             ContentSafetyService contentSafetyService,
                             BroadcastService broadcastService,
                             ObjectMapper objectMapper) {
        this.coreClient = coreClient;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.sessionTracker = sessionTracker;
        this.rateLimitService = rateLimitService;
        this.contentSafetyService = contentSafetyService;
        this.broadcastService = broadcastService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<?> createMessage(@RequestBody Map<String, Object> body) {
        Object reqIdObj = body.get("req_id");
        String reqId = (reqIdObj == null || "null".equals(String.valueOf(reqIdObj)) || String.valueOf(reqIdObj).isBlank())
                ? UUID.randomUUID().toString()
                : String.valueOf(reqIdObj);

        Object questionObj = body.get("question");
        String question = questionObj == null ? "" : questionObj.toString();

        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());
        boolean isPrivate = "true".equals(String.valueOf(body.get("private")));

        if (!rateLimitService.isAllowed(userId)) {
            long retryAfter = rateLimitService.getRemainingSeconds(userId);
            return ResponseEntity.status(429).body(Map.of(
                    "error", "请求过于频繁，请 " + retryAfter + " 秒后再试",
                    "retry_after", retryAfter
            ));
        }

        String safetyResult = contentSafetyService.detectSensitive(question);
        if (safetyResult != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", contentSafetyService.getLabelHint(safetyResult)
            ));
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
        Object insertResult = coreClient.insertMessage(msgPayload);

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
            coreClient.chatProcess(messagePayload);
        }

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "queued", "user_id", userId));
    }

    @PostMapping("/with-file")
    public ResponseEntity<?> createMessageWithFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question,
            @RequestParam("user_id") Long userId,
            @RequestParam("req_id") String reqId) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        if (!rateLimitService.isAllowed(userId)) {
            long retryAfter = rateLimitService.getRemainingSeconds(userId);
            return ResponseEntity.status(429).body(Map.of(
                    "error", "请求过于频繁，请 " + retryAfter + " 秒后再试",
                    "retry_after", retryAfter
            ));
        }

        String safetyResult = contentSafetyService.detectSensitive(question);
        if (safetyResult != null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", contentSafetyService.getLabelHint(safetyResult)
            ));
        }

        User user = resolveOrCreateMessageUser(userId);
        userId = user.id;

        try {
            coreClient.chatProcessWithFile(reqId, userId, question,
                    file.getOriginalFilename(), file.getBytes(), file.getContentType());
        } catch (Exception ex) {
            log.error("processWithFile failed: {}", ex.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    WsMessage.error("文件处理失败: " + ex.getMessage()).withReqId(reqId).toMap());
        }

        return ResponseEntity.accepted().body(Map.of("req_id", reqId, "status", "queued", "user_id", userId));
    }

    @SuppressWarnings("unchecked")
    private User resolveOrCreateMessageUser(Long rawUserId) {
        if (rawUserId == null) rawUserId = 0L;

        Object result = coreClient.getUserById(rawUserId);
        if (result != null) {
            return objectMapper.convertValue(result, User.class);
        }

        String guestEmail = "user_" + rawUserId + "@chat.local";
        Object emailResult = coreClient.getUserByEmail(guestEmail);
        if (emailResult != null) {
            return objectMapper.convertValue(emailResult, User.class);
        }

        Map<String, Object> newUser = new HashMap<>();
        newUser.put("email", guestEmail);
        newUser.put("name", "");
        newUser.put("guestName", "用户" + rawUserId);
        newUser.put("role", "user");
        newUser.put("passwordHash", "");
        coreClient.insertUser(newUser);

        Object created = coreClient.getUserByEmail(guestEmail);
        return objectMapper.convertValue(created, User.class);
    }

    @GetMapping
    public ResponseEntity<?> listMessages(@RequestParam(value = "user_id", defaultValue = "0") Long userId) {
        return ResponseEntity.ok(coreClient.listMessages(userId));
    }

    @GetMapping("/recent")
    public ResponseEntity<?> listRecentPrivate(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(coreClient.listRecentPrivate(userId));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchPrivateMessages(@RequestParam("user_id") Long userId,
                                                    @RequestParam("keyword") String keyword,
                                                    @RequestParam(value = "page", defaultValue = "1") int page,
                                                    @RequestParam(value = "size", defaultValue = "5") int size) {
        return ResponseEntity.ok(coreClient.searchPrivateMessages(userId, keyword, page, size));
    }

    @GetMapping("/context")
    public ResponseEntity<?> getContextMessages(@RequestParam("user_id") Long userId,
                                                 @RequestParam("msg_id") Long msgId) {
        return ResponseEntity.ok(coreClient.getContextMessages(userId, msgId));
    }

    @GetMapping("/all")
    public ResponseEntity<?> listAllMessages() {
        return ResponseEntity.ok(coreClient.listAllMessages());
    }

    @GetMapping("/questions")
    public ResponseEntity<?> listQuestionsOnly() {
        return ResponseEntity.ok(coreClient.listQuestionsOnly());
    }

    @GetMapping("/search-all")
    public ResponseEntity<?> searchQuestions(@RequestParam("q") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(Collections.emptyList());
        }
        return ResponseEntity.ok(coreClient.searchQuestions(keyword.trim()));
    }

    @GetMapping("/{id}/answer")
    public ResponseEntity<?> getAnswerById(@PathVariable Long id) {
        return ResponseEntity.ok(coreClient.getAnswerById(id));
    }

    @GetMapping("/online-count")
    public ResponseEntity<?> getOnlineCount(@RequestParam(value = "page", defaultValue = "global") String page) {
        return ResponseEntity.ok(Map.of("count", sessionTracker.getCount(page)));
    }

    @PostMapping("/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        Long userId = body.get("user_id") == null ? 0L : Long.parseLong(body.get("user_id").toString());

        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "req_id 不能为空"));
        }
        if (userId == null || userId <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "user_id 不能为空"));
        }

        try {
            coreClient.chatRegenerate(reqId, userId);
            return ResponseEntity.accepted().body(Map.of("status", "regenerating", "old_req_id", reqId));
        } catch (Exception ex) {
            log.error("[ERROR] regenerate 失败 reqId={}: {}", reqId, ex.getMessage(), ex);
            return ResponseEntity.status(500).body(Map.of("error", "重新生成失败: " + ex.getMessage()));
        }
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> body) {
        String reqId = body.get("req_id") == null ? null : String.valueOf(body.get("req_id"));
        if (reqId == null || reqId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "req_id 不能为空"));
        }
        coreClient.chatStop(reqId);
        log.info("[INFO] 收到停止请求 reqId={}", reqId);
        return ResponseEntity.ok(Map.of("status", "stopped", "req_id", reqId));
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
