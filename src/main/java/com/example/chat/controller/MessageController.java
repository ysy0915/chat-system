package com.example.chat.controller;

import com.example.chat.entity.Message;
import com.example.chat.entity.User;
import com.example.chat.repository.MessageRepository;
import com.example.chat.repository.UserRepository;
import com.example.chat.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public class MessageController {
    private static final Logger log = LoggerFactory.getLogger(MessageController.class);
    private final MessageRepository messageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final com.example.chat.service.ChatProcessor chatProcessor;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final boolean useRabbit;
    private final com.example.chat.config.WebSocketSessionTracker sessionTracker;
    private final com.example.chat.service.RateLimitService rateLimitService;
    private final com.example.chat.service.ContentSafetyService contentSafetyService;
    private final BroadcastService broadcastService;

    public MessageController(MessageRepository messageRepository, RabbitTemplate rabbitTemplate, com.example.chat.service.ChatProcessor chatProcessor, UserRepository userRepository, SimpMessagingTemplate simpMessagingTemplate, @org.springframework.beans.factory.annotation.Value("${app.use-rabbit:true}") boolean useRabbit, com.example.chat.config.WebSocketSessionTracker sessionTracker, com.example.chat.service.RateLimitService rateLimitService, com.example.chat.service.ContentSafetyService contentSafetyService, BroadcastService broadcastService) {
        this.messageRepository = messageRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.chatProcessor = chatProcessor;
        this.userRepository = userRepository;
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.useRabbit = useRabbit;
        this.sessionTracker = sessionTracker;
        this.rateLimitService = rateLimitService;
        this.contentSafetyService = contentSafetyService;
        this.broadcastService = broadcastService;
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

        log.debug("[DEBUG] createMessage body={} resolved reqId={}", body, reqId);
        Message m = new Message();
        m.reqId = reqId;
        m.userId = userId;
        m.question = question;
        m.status = "queued";
        m.isPrivate = isPrivate ? 1 : 0;
        messageRepository.insert(m);

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
        Map<String, Object> messagePayload = new HashMap<>();
        messagePayload.put("req_id", reqId);
        messagePayload.put("user_id", userId);
        messagePayload.put("question", question);
        messagePayload.put("private", String.valueOf(isPrivate));
        if (preferred != null) {
            messagePayload.put("preferred_model_config_id", preferred);
        }

        if (aiAnswer) {
            if (useRabbit) {
                try {
                    rabbitTemplate.convertAndSend("chat.exchange", "chat.request", messagePayload);
                } catch (Exception ex) {
                    log.warn("Rabbit send failed, falling back: {}", ex.getMessage());
                    chatProcessor.process(messagePayload);
                }
            } else {
                chatProcessor.process(messagePayload);
            }
        }

        return ResponseEntity.accepted().body(Map.of("id", m.id, "req_id", reqId, "status", "queued", "user_id", userId));    }

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

        String mimeType = file.getContentType();
        boolean isImage = mimeType != null && mimeType.startsWith("image/");
        String storedQuestion;

        if (isImage) {
            storedQuestion = question + " [附带图片: " + file.getOriginalFilename() + "]";
        } else {
            try {
                String lowerName = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
                String fileText;
                if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
                    fileText = extractExcelText(file.getBytes());
                } else if (lowerName.endsWith(".pptx") || lowerName.endsWith(".ppt")) {
                    fileText = extractPptText(file.getBytes());
                } else {
                    fileText = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                if (fileText.length() > 10000) {
                    fileText = fileText.substring(0, 10000) + "\n...[内容过长，已截断]";
                }
                storedQuestion = question + "\n\n--- 以下是文件 [" + file.getOriginalFilename() + "] 的内容 ---\n" + fileText + "\n--- 文件内容结束 ---";
            } catch (Exception ex) {
                log.warn("[WARN] Failed to extract file content for storage: {}", ex.getMessage());
                storedQuestion = question + " [附带文件: " + file.getOriginalFilename() + "]";
            }
        }

        Message m = new Message();
        m.reqId = reqId;
        m.userId = userId;
        m.question = storedQuestion;
        m.status = "queued";
        m.isPrivate = 1;
        messageRepository.insert(m);

        try {
            chatProcessor.processWithFile(reqId, userId, question,
                    file.getOriginalFilename(), file.getBytes(), mimeType);
        } catch (Exception ex) {
            log.error("processWithFile failed: {}", ex.getMessage());
            broadcastService.broadcast("/topic/user." + userId,
                    Map.of("type", "error", "req_id", reqId, "message", "文件处理失败: " + ex.getMessage()));
        }

        return ResponseEntity.accepted().body(Map.of("id", m.id, "req_id", reqId, "status", "queued", "user_id", userId));
    }

    private User resolveOrCreateMessageUser(Long rawUserId) {
        if (rawUserId == null) {
            rawUserId = 0L;
        }

        User existingById = userRepository.findById(rawUserId);
        if (existingById != null) {
            return existingById;
        }

        String guestEmail = "user_" + rawUserId + "@chat.local";
        User existingByEmail = userRepository.findByEmail(guestEmail);
        if (existingByEmail != null) {
            return existingByEmail;
        }

        User newUser = new User();
        newUser.email = guestEmail;
        newUser.name = "";
        newUser.guestName = "用户" + rawUserId;
        newUser.role = "user";
        newUser.passwordHash = "";
        userRepository.insert(newUser);
        return newUser;
    }

    private String extractExcelText(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(data))) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                var sheet = wb.getSheetAt(i);
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    var row = sheet.getRow(r);
                    if (row == null) { sb.append("\n"); continue; }
                    java.util.List<String> cells = new java.util.ArrayList<>();
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        var cell = row.getCell(c);
                        cells.add(cell != null ? cell.toString().trim() : "");
                    }
                    sb.append(String.join("\t", cells)).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractPptText(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xslf.usermodel.XMLSlideShow ppt = new org.apache.poi.xslf.usermodel.XMLSlideShow(new java.io.ByteArrayInputStream(data))) {
            var slides = ppt.getSlides();
            for (int i = 0; i < slides.size(); i++) {
                sb.append("=== Slide ").append(i + 1).append(" ===\n");
                for (var shape : slides.get(i).getShapes()) {
                    if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape ts) {
                        String t = ts.getText().trim();
                        if (!t.isEmpty()) sb.append(t).append("\n");
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    @GetMapping
    public ResponseEntity<?> listMessages(@RequestParam(value = "user_id", defaultValue = "0") Long userId) {
        List<Message> list = messageRepository.findByUserId(userId);
        return ResponseEntity.ok(list);
    }

    @GetMapping("/all")
    public ResponseEntity<?> listAllMessages() {
        List<Message> list = messageRepository.findAllMessages();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/questions")
    public ResponseEntity<?> listQuestionsOnly() {
        List<Message> list = messageRepository.findQuestionsOnly();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchQuestions(@RequestParam("q") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.ok(java.util.Collections.emptyList());
        }
        List<Message> list = messageRepository.searchQuestions(keyword.trim());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}/answer")
    public ResponseEntity<?> getAnswerById(@PathVariable Long id) {
        Message m = messageRepository.findAnswerById(id);
        if (m == null || m.answerJson == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("answer", m.answerJson));
    }

    @GetMapping("/online-count")
    public ResponseEntity<?> getOnlineCount(@RequestParam(value = "page", defaultValue = "global") String page) {
        return ResponseEntity.ok(Map.of("count", sessionTracker.getCount(page)));
    }

    @MessageMapping("/online.register")
    public void handleOnlineRegister(@Header("simpSessionId") String sessionId, Map<String, String> payload) {
        String userId = payload.get("userId");
        String page = payload.get("page");
        if (userId != null) {
            String displayName = "用户" + userId;
            try {
                Long uid = Long.parseLong(userId);
                User user = userRepository.findById(uid);
                if (user != null && user.nickname != null && !user.nickname.isBlank()) {
                    displayName = user.nickname;
                }
            } catch (NumberFormatException ignored) {
            } catch (Exception e) {
                log.warn("[WARN] Failed to lookup user: {}", e.getMessage());
            }
            sessionTracker.registerUser(sessionId, userId, displayName, page);
        }
    }

    @MessageMapping("/online.unregister")
    public void handleOnlineUnregister(@Header("simpSessionId") String sessionId, Map<String, String> payload) {
        String page = payload.get("page");
        sessionTracker.unregisterUser(sessionId, page);
    }

}
