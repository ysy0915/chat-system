package com.example.chat.internal;

import com.example.chat.entity.DebateRecord;
import com.example.chat.repository.DebateRecordRepository;
import com.example.chat.client.GraphClient;
import com.example.chat.service.ChatProcessor;
import com.example.chat.service.DebateProcessor;
import com.example.chat.service.TreeHoleQueryService;
import com.example.chat.service.TreeHoleService;
import com.example.chat.repository.ModelConfigRepository;
import com.example.chat.repository.TreeHoleRepository;
import com.example.chat.repository.UserRepository;
import com.example.chat.entity.ModelConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.example.chat.entity.Message;
import com.example.chat.entity.TreeHoleMessage;
import com.example.chat.entity.User;
import com.example.chat.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内部 API Controller（仅供 chat-web 调用，不对外暴露）
 * 路径前缀：/internal/
 */
@Tag(name = "内部API", description = "供 chat-web 调用的核心内部接口")
@RestController
@RequestMapping("/internal")
public class InternalApiController {

    private static final Logger log = LoggerFactory.getLogger(InternalApiController.class);

    private final ChatProcessor chatProcessor;
    private final TreeHoleService treeHoleService;
    private final TreeHoleQueryService treeHoleQueryService;
    private final DebateProcessor debateProcessor;
    private final MessageRepository messageRepository;
    private final TreeHoleRepository treeHoleRepository;
    private final DebateRecordRepository debateRecordRepository;
    private final UserRepository userRepository;
    private final ModelConfigRepository modelConfigRepository;
    private final ObjectMapper objectMapper;
    private final GraphClient graphClient;
    private final com.example.chat.observability.TraceRecorder traceRecorder;
    private final com.example.chat.observability.ErrorAggregator errorAggregator;

    public InternalApiController(
            ChatProcessor chatProcessor,
            TreeHoleService treeHoleService,
            TreeHoleQueryService treeHoleQueryService,
            DebateProcessor debateProcessor,
            MessageRepository messageRepository,
            TreeHoleRepository treeHoleRepository,
            DebateRecordRepository debateRecordRepository,
            UserRepository userRepository,
            ModelConfigRepository modelConfigRepository,
            ObjectMapper objectMapper,
            @Autowired(required = false) GraphClient graphClient,
            @Autowired(required = false) com.example.chat.observability.TraceRecorder traceRecorder,
            @Autowired(required = false) com.example.chat.observability.ErrorAggregator errorAggregator) {
        this.chatProcessor = chatProcessor;
        this.treeHoleService = treeHoleService;
        this.treeHoleQueryService = treeHoleQueryService;
        this.debateProcessor = debateProcessor;
        this.messageRepository = messageRepository;
        this.treeHoleRepository = treeHoleRepository;
        this.debateRecordRepository = debateRecordRepository;
        this.userRepository = userRepository;
        this.modelConfigRepository = modelConfigRepository;
        this.objectMapper = objectMapper;
        this.graphClient = graphClient;
        this.traceRecorder = traceRecorder;
        this.errorAggregator = errorAggregator;
    }

    // ==================== 群聊 ====================

    @Operation(summary = "处理群聊消息", description = "接收并异步处理群聊消息，返回 accepted 表示已接收")
    @PostMapping("/chat/process")
    public ResponseEntity<?> process(@RequestBody Map<String, Object> payload) {
        log.info("[Internal] chat/process received: req_id={}, user_id={}, private={}", 
                payload.get("req_id"), payload.get("user_id"), payload.get("private"));
        chatProcessor.process(payload);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Operation(summary = "处理带文件的群聊消息", description = "接收 Base64 编码的文件数据，异步处理含附件的群聊消息")
    @PostMapping("/chat/process-with-file")
    public ResponseEntity<?> processWithFile(@RequestBody Map<String, Object> payload) {
        try {
            String reqId = (String) payload.get("req_id");
            Object userIdObj = payload.get("user_id");
            if (userIdObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 user_id 字段"));
            }
            Long userId = ((Number) userIdObj).longValue();
            String question = (String) payload.get("question");
            String fileName = (String) payload.get("file_name");
            String mimeType = (String) payload.get("mime_type");
            Object fileDataObj = payload.get("file_data");
            if (fileDataObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 file_data 字段"));
            }
            byte[] fileBytes = Base64.getDecoder().decode((String) fileDataObj);
            chatProcessor.processWithFile(reqId, userId, question, fileName, fileBytes, mimeType);
            return ResponseEntity.ok(Map.of("status", "accepted"));
        } catch (Exception e) {
            log.error("[Internal] processWithFile error", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "重新生成群聊回复", description = "根据请求ID重新生成上一次群聊回答")
    @PostMapping("/chat/regenerate")
    public ResponseEntity<?> regenerate(@RequestBody Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        Object userIdObj = payload.get("user_id");
        if (userIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 user_id 字段"));
        }
        Long userId = ((Number) userIdObj).longValue();
        chatProcessor.regenerate(reqId, userId);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Operation(summary = "停止群聊生成", description = "停止正在进行的群聊消息流式生成")
    @PostMapping("/chat/stop")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> payload) {
        String reqId = (String) payload.get("req_id");
        chatProcessor.requestStop(reqId);
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    // ==================== 消息查询 ====================

    @Operation(summary = "查询用户消息列表", description = "根据用户ID查询该用户的所有消息记录")
    @GetMapping("/messages")
    public ResponseEntity<?> listMessages(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(messageRepository.findByUserId(userId));
    }

    @Operation(summary = "查询用户最近私聊消息", description = "获取指定用户最近的5条私聊消息记录")
    @GetMapping("/messages/recent")
    public ResponseEntity<?> listRecentPrivate(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(messageRepository.findRecentPrivateByUserId(userId, 5));
    }

    @Operation(summary = "搜索私聊消息", description = "按关键词搜索用户的私聊消息，支持分页")
    @GetMapping("/messages/search")
    public ResponseEntity<?> searchPrivateMessages(
            @Parameter(description = "用户ID") @RequestParam("user_id") Long userId,
            @Parameter(description = "搜索关键词") @RequestParam("keyword") String keyword,
            @Parameter(description = "页码，从1开始") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(value = "size", defaultValue = "5") int size) {
        int offset = (page - 1) * size;
        List<?> results = messageRepository.searchPrivateMessages(userId, keyword, offset, size);
        int total = messageRepository.countSearchPrivateMessages(userId, keyword);
        int totalPages = (int) Math.ceil((double) total / size);
        return ResponseEntity.ok(Map.of("results", results, "total", total, "totalPages", totalPages, "page", page));
    }

    @Operation(summary = "获取消息上下文", description = "获取指定消息前后的上下文消息")
    @GetMapping("/messages/context")
    public ResponseEntity<?> getContextMessages(
            @Parameter(description = "用户ID") @RequestParam("user_id") Long userId,
            @Parameter(description = "消息ID") @RequestParam("msg_id") Long msgId) {
        return ResponseEntity.ok(messageRepository.findContextAround(userId, msgId));
    }

    @Operation(summary = "查询全部消息", description = "获取系统中所有消息记录")
    @GetMapping("/messages/all")
    public ResponseEntity<?> listAllMessages() {
        return ResponseEntity.ok(messageRepository.findAllMessages());
    }

    @Operation(summary = "查询所有问题", description = "获取系统中所有的提问消息（不含回答）")
    @GetMapping("/messages/questions")
    public ResponseEntity<?> listQuestionsOnly() {
        return ResponseEntity.ok(messageRepository.findQuestionsOnly());
    }

    @Operation(summary = "全局搜索问题", description = "在所有问题中按关键词搜索匹配的问题")
    @GetMapping("/messages/search-all")
    public ResponseEntity<?> searchQuestions(@Parameter(description = "搜索关键词") @RequestParam("q") String keyword) {
        return ResponseEntity.ok(messageRepository.searchQuestions(keyword.trim()));
    }

    @Operation(summary = "根据消息ID查询回答", description = "获取指定消息ID对应的回答内容")
    @GetMapping("/messages/{id}/answer")
    public ResponseEntity<?> getAnswerById(@Parameter(description = "消息ID") @PathVariable Long id) {
        return ResponseEntity.ok(messageRepository.findAnswerById(id));
    }

    @Operation(summary = "根据请求ID查询消息", description = "通过请求唯一标识查询对应的消息记录")
    @GetMapping("/messages/by-req-id/{reqId}")
    public ResponseEntity<?> getByReqId(@Parameter(description = "请求ID") @PathVariable String reqId) {
        Message m = messageRepository.findByReqId(reqId);
        return m != null ? ResponseEntity.ok(m) : ResponseEntity.notFound().build();
    }

    @Operation(summary = "插入消息记录", description = "手动插入一条消息到数据库")
    @PostMapping("/messages/insert")
    public ResponseEntity<?> insertMessage(@RequestBody Map<String, Object> payload) {
        Message m = objectMapper.convertValue(payload, Message.class);
        messageRepository.insert(m);
        return ResponseEntity.ok(Map.of("id", m.id));
    }

    // ==================== 树洞 ====================

    @Operation(summary = "树洞提问", description = "向树洞匿名提问并流式获取AI回复")
    @PostMapping("/treehole/ask")
    public ResponseEntity<?> treeHoleAsk(@RequestBody Map<String, Object> payload) {
        Object userIdObj = payload.get("user_id");
        if (userIdObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 user_id 字段"));
        }
        treeHoleService.askAndStream(
                ((Number) userIdObj).longValue(),
                (String) payload.get("question"),
                (String) payload.getOrDefault("mood", ""));
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Operation(summary = "树洞带文件提问", description = "向树洞匿名提问，附带 Base64 编码的文件内容")
    @PostMapping("/treehole/ask-with-file")
    public ResponseEntity<?> treeHoleAskWithFile(@RequestBody Map<String, Object> payload) {
        try {
            Object userIdObj = payload.get("user_id");
            if (userIdObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 user_id 字段"));
            }
            Object fileDataObj = payload.get("file_data");
            if (fileDataObj == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "缺少 file_data 字段"));
            }
            TreeHoleMessage result = treeHoleService.askWithFile(
                    ((Number) userIdObj).longValue(),
                    (String) payload.get("question"),
                    (String) payload.getOrDefault("mood", ""),
                    (String) payload.get("file_name"),
                    Base64.getDecoder().decode((String) fileDataObj));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @Operation(summary = "树洞重新生成回复", description = "根据请求ID重新生成上一次树洞回答")
    @PostMapping("/treehole/regenerate")
    public ResponseEntity<?> treeHoleRegenerate(@RequestBody Map<String, Object> payload) {
        treeHoleService.regenerate(
                (String) payload.get("req_id"),
                ((Number) payload.get("user_id")).longValue());
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Operation(summary = "停止树洞生成", description = "停止正在进行的树洞回复流式生成")
    @PostMapping("/treehole/stop")
    public ResponseEntity<?> treeHoleStop(@RequestBody Map<String, Object> payload) {
        treeHoleService.requestStop((String) payload.get("req_id"));
        return ResponseEntity.ok(Map.of("status", "stopped"));
    }

    @Operation(summary = "树洞历史记录", description = "获取指定用户的树洞对话历史")
    @GetMapping("/treehole/history")
    public ResponseEntity<?> treeHoleHistory(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(treeHoleQueryService.getHistory(userId));
    }

    @Operation(summary = "树洞最近记录", description = "获取用户最近的5条树洞对话记录")
    @GetMapping("/treehole/recent")
    public ResponseEntity<?> treeHoleRecent(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(treeHoleQueryService.getRecentHistory(userId, 5));
    }

    @Operation(summary = "树洞搜索", description = "按关键词搜索树洞对话记录，支持分页")
    @GetMapping("/treehole/search")
    public ResponseEntity<?> treeHoleSearch(
            @Parameter(description = "用户ID") @RequestParam("user_id") Long userId,
            @Parameter(description = "搜索关键词") @RequestParam("keyword") String keyword,
            @Parameter(description = "页码，从1开始") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "每页大小") @RequestParam(value = "size", defaultValue = "5") int size) {
        int offset = (page - 1) * size;
        List<?> results = treeHoleQueryService.searchHistory(userId, keyword, offset, size);
        int total = treeHoleQueryService.countSearchHistory(userId, keyword);
        int totalPages = (int) Math.ceil((double) total / size);
        return ResponseEntity.ok(Map.of("results", results, "total", total, "totalPages", totalPages, "page", page));
    }

    @Operation(summary = "树洞消息上下文", description = "获取树洞对话中指定消息的前后上下文")
    @GetMapping("/treehole/context")
    public ResponseEntity<?> treeHoleContext(
            @Parameter(description = "用户ID") @RequestParam("user_id") Long userId,
            @Parameter(description = "消息ID") @RequestParam("msg_id") Long msgId) {
        return ResponseEntity.ok(treeHoleQueryService.getContextAround(userId, msgId));
    }

    @Operation(summary = "插入树洞消息", description = "手动插入一条树洞消息记录")
    @PostMapping("/treehole/insert")
    public ResponseEntity<?> insertTreeHole(@RequestBody Map<String, Object> payload) {
        TreeHoleMessage m = objectMapper.convertValue(payload, TreeHoleMessage.class);
        treeHoleRepository.insert(m);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== 辩论 ====================

    @Operation(summary = "开始辩论", description = "发起一场AI辩论，指定正反方观点并开始辩论流程")
    @PostMapping("/debate/start")
    public ResponseEntity<?> debateStart(@RequestBody Map<String, Object> payload) {
        debateProcessor.process(payload);
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    @Operation(summary = "辩论记录列表", description = "查询指定用户的所有辩论记录")
    @GetMapping("/debate/records")
    public ResponseEntity<?> debateRecords(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(debateRecordRepository.findByUserId(userId));
    }

    @Operation(summary = "最近辩论记录", description = "获取用户最近的辩论记录")
    @GetMapping("/debate/records/recent")
    public ResponseEntity<?> debateRecordsRecent(@Parameter(description = "用户ID") @RequestParam("user_id") Long userId) {
        return ResponseEntity.ok(debateRecordRepository.findByUserId(userId));
    }

    @Operation(summary = "辩论记录详情", description = "根据ID查询辩论记录的详细信息")
    @GetMapping("/debate/records/{id}")
    public ResponseEntity<?> debateRecordById(@Parameter(description = "辩论记录ID") @PathVariable Long id) {
        return ResponseEntity.ok(debateRecordRepository.findById(id));
    }

    @Operation(summary = "插入辩论记录", description = "手动插入一条辩论记录")
    @PostMapping("/debate/records/insert")
    public ResponseEntity<?> insertDebateRecord(@RequestBody Map<String, Object> payload) {
        DebateRecord r = objectMapper.convertValue(payload, DebateRecord.class);
        debateRecordRepository.insert(r);
        return ResponseEntity.ok(Map.of("id", r.id));
    }

    // ==================== 用户 ====================

    @Operation(summary = "根据ID查询用户", description = "通过用户ID获取用户详细信息")
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUserById(@Parameter(description = "用户ID") @PathVariable Long id) {
        return ResponseEntity.ok(userRepository.findById(id));
    }

    @Operation(summary = "根据邮箱查询用户", description = "通过邮箱地址获取用户信息")
    @GetMapping("/users/email/{email}")
    public ResponseEntity<?> getUserByEmail(@Parameter(description = "用户邮箱") @PathVariable String email) {
        return ResponseEntity.ok(userRepository.findByEmail(email));
    }

    @Operation(summary = "插入用户记录", description = "手动插入一条用户信息记录")
    @PostMapping("/users/insert")
    public ResponseEntity<?> insertUser(@RequestBody Map<String, Object> payload) {
        User u = objectMapper.convertValue(payload, User.class);
        userRepository.insert(u);
        return ResponseEntity.ok(Map.of("id", u.id));
    }

    // ==================== 模型配置 ====================

    @Operation(summary = "模型配置列表", description = "获取所有AI模型的配置信息")
    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        return ResponseEntity.ok(modelConfigRepository.findAll());
    }

    @Operation(summary = "模型配置详情", description = "根据ID查询模型配置的详细信息")
    @GetMapping("/models/{id}")
    public ResponseEntity<?> getModelById(@Parameter(description = "模型配置ID") @PathVariable Long id) {
        return ResponseEntity.ok(modelConfigRepository.findById(id));
    }

    @Operation(summary = "保存模型配置", description = "更新或保存AI模型的配置信息")
    @PostMapping("/models/save")
    public ResponseEntity<?> saveModel(@RequestBody Map<String, Object> payload) {
        ModelConfig m = objectMapper.convertValue(payload, ModelConfig.class);
        modelConfigRepository.update(m);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ==================== 可观测性 ====================

    @Operation(summary = "最近追踪记录", description = "获取最近的执行追踪记录，用于性能分析和调试")
    @GetMapping("/traces")
    public ResponseEntity<?> getRecentTraces(@Parameter(description = "获取记录数，默认20，最大1000") @RequestParam(value = "n", required = false) Integer n) {
        int count = n == null ? 20 : Math.max(1, Math.min(n, 1000));
        if (traceRecorder == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "traces", traceRecorder.getRecentTraces(count)));
    }

    @Operation(summary = "搜索追踪记录", description = "按关键词搜索执行追踪记录")
    @GetMapping("/traces/search")
    public ResponseEntity<?> searchTraces(@Parameter(description = "搜索关键词") @RequestParam(value = "keyword", required = false) String keyword) {
        if (traceRecorder == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "traces", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "traces", traceRecorder.searchTraces(keyword)));
    }

    @Operation(summary = "错误统计", description = "获取系统错误聚合统计信息和Top错误列表")
    @GetMapping("/errors")
    public ResponseEntity<?> getErrorStats() {
        if (errorAggregator == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "errors", Collections.emptyList()));
        }
        return ResponseEntity.ok(Map.of("enabled", true, "errors", errorAggregator.getErrorStats(),
                "topErrors", errorAggregator.getTopErrors(10)));
    }

    // ==================== 知识图谱（运行时已迁至 chat-llm，经 GraphClient 跨进程调用） ====================

    @Operation(summary = "获取知识图谱", description = "获取知识图谱的全部节点和关系边（chat-llm）")
    @GetMapping("/graph")
    public ResponseEntity<?> getGraph(
            @Parameter(description = "返回节点数上限，默认100") @RequestParam(value = "limit", defaultValue = "100") int limit,
            @Parameter(description = "实体最低权重（关系数），默认1") @RequestParam(value = "minEntityWeight", defaultValue = "1") int minEntityWeight,
            @Parameter(description = "关系最低权重（累计次数），默认1") @RequestParam(value = "minRelationWeight", defaultValue = "1") int minRelationWeight) {
        if (graphClient == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "nodes", Collections.emptyList(), "edges", Collections.emptyList()));
        }
        return ResponseEntity.ok(graphClient.getGraph(limit, minEntityWeight, minRelationWeight));
    }

    @Operation(summary = "搜索知识图谱", description = "按关键词在知识图谱中搜索实体和关系（chat-llm）")
    @GetMapping("/graph/search")
    public ResponseEntity<?> searchGraph(
            @Parameter(description = "搜索关键词") @RequestParam("keyword") String keyword,
            @Parameter(description = "返回结果数上限，默认30") @RequestParam(value = "limit", defaultValue = "30") int limit,
            @Parameter(description = "实体最低权重（关系数），默认1") @RequestParam(value = "minEntityWeight", defaultValue = "1") int minEntityWeight,
            @Parameter(description = "关系最低权重（累计次数），默认1") @RequestParam(value = "minRelationWeight", defaultValue = "1") int minRelationWeight) {
        if (graphClient == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "nodes", Collections.emptyList(), "edges", Collections.emptyList()));
        }
        return ResponseEntity.ok(graphClient.searchEntities(keyword, limit, minEntityWeight, minRelationWeight));
    }

    @Operation(summary = "知识图谱统计", description = "获取知识图谱的实体数和关系数统计（chat-llm）")
    @GetMapping("/graph/stats")
    public ResponseEntity<?> getGraphStats() {
        if (graphClient == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "entityCount", 0, "relationCount", 0));
        }
        return ResponseEntity.ok(graphClient.getStats());
    }

    @Operation(summary = "批量导入知识图谱", description = "触发批量从数据库导入数据到知识图谱（chat-llm）")
    @PostMapping("/graph/import")
    public ResponseEntity<?> importToGraph() {
        if (graphClient == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "message", "知识图谱服务未启用"));
        }
        return ResponseEntity.ok(graphClient.startBatchImport());
    }

    @Operation(summary = "导入状态查询", description = "查询知识图谱批量导入的进度和状态（chat-llm）")
    @GetMapping("/graph/import/status")
    public ResponseEntity<?> getImportStatus() {
        if (graphClient == null) {
            return ResponseEntity.ok(Map.of("enabled", false, "importing", false));
        }
        return ResponseEntity.ok(graphClient.getImportStatus());
    }
}
