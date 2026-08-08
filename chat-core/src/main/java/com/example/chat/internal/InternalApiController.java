package com.example.chat.internal;

import com.example.chat.entity.*;
import com.example.chat.repository.*;
import com.example.chat.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 内部 API Controller（仅供 chat-web 调用，不对外暴露）
 * 路径前缀：/internal/
 */
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    private static final Logger log = LoggerFactory.getLogger(InternalApiController.class);

    private final ChatProcessor chatProcessor;
    private final TreeHoleService treeHoleService;
    private final DebateProcessor debateProcessor;
    private final MessageRepository messageRepository;
    private final TreeHoleRepository treeHoleRepository;
    private final DebateRecordRepository debateRecordRepository;
    private final UserRepository userRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final KnowledgeGraphService knowledgeGraphService;

    public InternalApiController(
            ChatProcessor chatProcessor,
            TreeHoleService treeHoleService,
            DebateProcessor debateProcessor,
            MessageRepository messageRepository,
            TreeHoleRepository treeHoleRepository,
            DebateRecordRepository debateRecordRepository,
            UserRepository userRepository,
            ModelConfigRepository modelConfigRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) KnowledgeGraphService knowledgeGraphService) {
        this.chatProcessor = chatProcessor;
        this.treeHoleService = treeHoleService;
        this.debateProcessor = debateProcessor;
        this.messageRepository = messageRepository;
        this.treeHoleRepository = treeHoleRepository;
        this.debateRecordRepository = debateRecordRepository;
        this.userRepository = userRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // ==================== 群聊 ====================

    @PostMapping("/chat/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> payload) {
        log.info("[Internal] chat/process received: req_id={}, user_id={}, private={}", 
                payload.get("req_id"), payload.get("user_id"), payload.get("private"));
        chatProcessor.process(payload);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @PostMapping("/chat/process-with-file")
    public ResponseEntity<?> processWithFile(@RequestBody Map<String, Object> payload) {
        try {
            String reqId = (String) payload.get("req_id");
            Long userId = ((Number) payload.get("user_id")).longValue();
            String question = (String) payload.get("question");
            String fileName = (String) payload.get("file_name");
            String mimeType = (String) payload.get("mime_type");
            byte[] fileBytes = Base64.getDecoder().decode((String) payload.get("file_data"));
            chatProcessor.processWithFile(reqId, userId, question, fileName, fileBytes, mimeType);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.error("[Internal] processWithFile error", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/chat/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Long userId = ((Number) payload.get("user_id")).longValue();
        chatProcessor.regenerate(reqId, userId);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @PostMapping("/chat/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        chatProcessor.requestStop(reqId);
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    // ==================== 消息查询 ====================

    @GetMapping("/messages")
    public ResponseEntity<?> listMessages(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(messageRepository.findByUserId(userId));
    }

    @GetMapping("/messages/recent")
    public ResponseEntity<?> listRecentPrivate(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(messageRepository.findRecentPrivateByUserId(userId, 5));
    }

    @GetMapping("/messages/search")
    public ResponseEntity<?> searchPrivateMessages(
            @RequestParam("user_id") Long userId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "5") int size) {
        int offset = (page - 1) * size;
        List<?> results = messageRepository.searchPrivateMessages(userId, keyword, offset, size);
        int total = messageRepository.countSearchPrivateMessages(userId, keyword);
        int totalPages = (int) Math.ceil((double) total / size);
        return ResponseEntity.ok(Map.of("results", results, "total", total, "totalPages", totalPages, "page", page));
    }

    @GetMapping("/messages/context")
    public ResponseEntity<?> getContextMessages(@RequestParam("user_id") Long userId, @RequestParam("msg_id") Long msgId) {
        return ResponseEntity.ok(messageRepository.findContextAround(userId, msgId));
    }

    @GetMapping("/messages/all")
    public ResponseEntity<?> listAllMessages() {
        return ResponseEntity.ok(messageRepository.findAllMessages());
    }

    @GetMapping("/messages/questions")
    public ResponseEntity<?> listQuestionsOnly() {
        return ResponseEntity.ok(messageRepository.findQuestionsOnly());
    }

    @GetMapping("/messages/search-all")
    public ResponseEntity<?> searchQuestions(@RequestParam("q") String keyword) {
        return ResponseEntity.ok(messageRepository.searchQuestions(keyword.trim()));
    }

    @GetMapping("/messages/{id}/answer")
    public ResponseEntity<?> getAnswerById(@PathVariable Long id) {
        return ResponseEntity.ok(messageRepository.findAnswerById(id));
    }

    @GetMapping("/messages/by-req-id/{reqId}")
    public ResponseEntity<?> getByReqId(@PathVariable String reqId) {
        Message m = messageRepository.findByReqId(reqId);
        return m != null ? ResponseEntity.ok(m) : ResponseEntity.notFound().build();
    }

    @PostMapping("/messages/insert")
    public ResponseEntity<?> insertMessage(@RequestBody Map<String, Object> payload) {
        Message m = objectMapper.convertValue(payload, Message.class);
        messageRepository.insert(m);
        return ResponseEntity.ok(Map.of("id", m.id));
    }

    // ==================== 树洞 ====================

    @PostMapping("/treehole/ask")
    public ResponseEntity<?> treeHoleAsk(@RequestBody Map<String, Object> payload) {
        treeHoleService.askAndStream(
                ((Number) payload.get("user_id")).longValue(),
                (String) payload.get("question"),
                (String) payload.getOrDefault("mood", ""));
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @PostMapping("/treehole/ask-with-file")
    public ResponseEntity<?> treeHoleAskWithFile(@RequestBody Map<String, Object> payload) {
        try {
            TreeHoleMessage result = treeHoleService.askWithFile(
                    ((Number) payload.get("user_id")).longValue(),
                    (String) payload.get("question"),
                    (String) payload.getOrDefault("mood", ""),
                    (String) payload.get("file_name"),
                    Base64.getDecoder().decode((String) payload.get("file_data")));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/treehole/regenerate")
    public ResponseEntity<?> treeHoleRegenerate(@RequestBody Map<String, Object> payload) {
        treeHoleService.regenerate(
                (String) payload.get("req_id"),
                ((Number) payload.get("user_id")).longValue());
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @PostMapping("/treehole/stop")
    public ResponseEntity<?> treeHoleStop(@RequestBody Map<String, Object> payload) {
        treeHoleService.requestStop((String) payload.get("req_id"));
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @GetMapping("/treehole/history")
    public ResponseEntity<?> treeHoleHistory(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(treeHoleService.getHistory(userId));
    }

    @GetMapping("/treehole/recent")
    public ResponseEntity<?> treeHoleRecent(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(treeHoleService.getRecentHistory(userId, 5));
    }

    @GetMapping("/treehole/search")
    public ResponseEntity<?> treeHoleSearch(
            @RequestParam("user_id") Long userId,
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "5") int size) {
        int offset = (page - 1) * size;
        List<?> results = treeHoleService.searchHistory(userId, keyword, offset, size);
        int total = treeHoleService.countSearchHistory(userId, keyword);
        int totalPages = (int) Math.ceil((double) total / size);
        return ResponseEntity.ok(Map.of("results", results, "total", total, "totalPages", totalPages, "page", page));
    }

    @GetMapping("/treehole/context")
    public ResponseEntity<?> treeHoleContext(@RequestParam("user_id") Long userId, @RequestParam("msg_id") Long msgId) {
        return ResponseEntity.ok(treeHoleService.getContextAround(userId, msgId));
    }

    @PostMapping("/treehole/insert")
    public ResponseEntity<?> insertTreeHole(@RequestBody Map<String, Object> payload) {
        TreeHoleMessage m = objectMapper.convertValue(payload, TreeHoleMessage.class);
        treeHoleRepository.insert(m);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== 辩论 ====================

    @PostMapping("/debate/start")
    public ResponseEntity<?> debateStart(@RequestBody Map<String, Object> payload) {
        debateProcessor.process(payload);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @GetMapping("/debate/records")
    public ResponseEntity<?> debateRecords(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(debateRecordRepository.findByUserId(userId));
    }

    @GetMapping("/debate/records/recent")
    public ResponseEntity<?> debateRecordsRecent(@RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(debateRecordRepository.findByUserId(userId));
    }

    @GetMapping("/debate/records/{id}")
    public ResponseEntity<?> debateRecordById(@PathVariable Long id) {
        return ResponseEntity.ok(debateRecordRepository.findById(id));
    }

    @PostMapping("/debate/records/insert")
    public ResponseEntity<?> insertDebateRecord(@RequestBody Map<String, Object> payload) {
        DebateRecord r = objectMapper.convertValue(payload, DebateRecord.class);
        debateRecordRepository.insert(r);
        return ResponseEntity.ok(Map.of("id", r.id));
    }

    // ==================== 用户 ====================

    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userRepository.findById(id));
    }

    @GetMapping("/users/email/{email}")
    public ResponseEntity<?> getUserByEmail(@PathVariable String email) {
        return ResponseEntity.ok(userRepository.findByEmail(email));
    }

    @PostMapping("/users/insert")
    public ResponseEntity<?> insertUser(@RequestBody Map<String, Object> payload) {
        User u = objectMapper.convertValue(payload, User.class);
        userRepository.insert(u);
        return ResponseEntity.ok(Map.of("id", u.id));
    }

    // ==================== 模型配置 ====================

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        return ResponseEntity.ok(modelConfigRepository.findAll());
    }

    @GetMapping("/models/{id}")
    public ResponseEntity<?> getModelById(@PathVariable Long id) {
        return ResponseEntity.ok(modelConfigRepository.findById(id));
    }

    @PostMapping("/models/save")
    public ResponseEntity<?> saveModel(@RequestBody Map<String, Object> payload) {
        ModelConfig m = objectMapper.convertValue(payload, ModelConfig.class);
        modelConfigRepository.update(m);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== 可观测性 ====================

    @Autowired(required = false)
    private com.example.chat.observability.TraceRecorder traceRecorder;

    @Autowired(required = false)
    private com.example.chat.observability.ErrorAggregator errorAggregator;

    @GetMapping("/traces")
    public ResponseEntity<?> getRecentTraces(@RequestParam(value = "n", required = false) Integer n) {
        int count = n == null ? 20 : Math.max(1, Math.min(n, 1000));
        if (traceRecorder == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "traces", traceRecorder.getRecentTraces(count)));
    }

    @GetMapping("/traces/search")
    public ResponseEntity<?> searchTraces(@RequestParam(value = "keyword", required = false) String keyword) {
        if (traceRecorder == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "traces", traceRecorder.searchTraces(keyword)));
    }

    @GetMapping("/errors")
    public ResponseEntity<?> getErrorStats() {
        if (errorAggregator == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "errors", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "errors", errorAggregator.getErrorStats(),
                "topErrors", errorAggregator.getTopErrors(10)));
    }

    // ==================== 知识图谱 ====================

    @GetMapping("/graph")
    public ResponseEntity<?> getGraph(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "nodes", Collections.emptyList(), "edges", Collections.emptyList()));
        }
        return ResponseEntity.ok(knowledgeGraphService.getGraph(limit));
    }

    @GetMapping("/graph/search")
    public ResponseEntity<?> searchGraph(@RequestParam("keyword") String keyword,
                                          @RequestParam(value = "limit", defaultValue = "30") int limit) {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "nodes", Collections.emptyList(), "edges", Collections.emptyList()));
        }
        return ResponseEntity.ok(knowledgeGraphService.searchEntities(keyword, limit));
    }

    @GetMapping("/graph/stats")
    public ResponseEntity<?> getGraphStats() {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "entityCount", 0, "relationCount", 0));
        }
        return ResponseEntity.ok(knowledgeGraphService.getStats());
    }

    @PostMapping("/graph/import")
    public ResponseEntity<?> importToGraph() {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "message", "知识图谱服务未启用"));
        }
        boolean started = knowledgeGraphService.startBatchImport();
        return ResponseEntity.ok(Map.of(
                "started", started,
                "importing", knowledgeGraphService.isImporting()
        ));
    }

    @GetMapping("/graph/import/status")
    public ResponseEntity<?> getImportStatus() {
        if (knowledgeGraphService == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "importing", false));
        }
        return ResponseEntity.ok(Map.of(
                "importing", knowledgeGraphService.isImporting(),
                "stats", knowledgeGraphService.getStats()
        ));
    }
}
