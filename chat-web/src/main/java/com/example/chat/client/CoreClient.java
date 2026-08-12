package com.example.chat.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.example.chat.exception.ChatServiceException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * chat-core 内部 API 客户端
 * chat-web 通过此客户端调用 chat-core 的 REST API
 */
@Service
public class CoreClient {

    private static final Logger log = LoggerFactory.getLogger(CoreClient.class);

    @Value("${app.core.base-url:http://127.0.0.1:9090}")
    private String coreBaseUrl;

    /** chat-llm 服务地址（RAG 知识库 /api/v1/rag/* 已迁移至 chat-llm） */
    @Value("${app.llm-service.base-url:http://127.0.0.1:9095}")
    private String llmBaseUrl;

    // =========================== RAG 知识库 ===========================

    private org.springframework.http.HttpHeaders authHeaders(String authHeader) {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        if (authHeader != null && !authHeader.isEmpty()) {
            h.set("Authorization", authHeader);
        }
        h.set("User-Agent", "chat-web");
        return h;
    }

    /** GET /api/v1/rag/kb - 知识库列表（chat-llm） */
    public Object listKnowledgeBases(String authHeader) {
        org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/rag/kb", org.springframework.http.HttpMethod.GET, entity, Object.class).getBody();
    }

    /** POST /api/v1/rag/kb - 创建知识库（chat-llm） */
    public Object createKnowledgeBase(Map<String, Object> body, String authHeader) {
        org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(body, authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/rag/kb", org.springframework.http.HttpMethod.POST, entity, Object.class).getBody();
    }

    /** DELETE /api/v1/rag/kb/{id} - 删除知识库（chat-llm） */
    public Object deleteKnowledgeBase(Long id, String authHeader) {
        org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/rag/kb/" + id, org.springframework.http.HttpMethod.DELETE, entity, Object.class).getBody();
    }

    /** GET /api/v1/rag/kb/{id}/documents - 文档列表（chat-llm） */
    public Object listDocuments(Long kbId, String authHeader) {
        org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/rag/kb/" + kbId + "/documents", org.springframework.http.HttpMethod.GET, entity, Object.class).getBody();
    }

    /** DELETE /api/v1/rag/documents/{docId} - 删除文档（chat-llm） */
    public Object deleteDocument(Long docId, String authHeader) {
        org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/rag/documents/" + docId, org.springframework.http.HttpMethod.DELETE, entity, Object.class).getBody();
    }

    /** POST /api/v1/rag/kb/{id}/documents - 上传文档 (multipart, param="file"，chat-llm) */
    public Object uploadDocument(Long kbId, MultipartFile file, String authHeader) {
        try {
            java.io.InputStream is = file.getInputStream();
            long len = file.getSize();
            org.springframework.core.io.InputStreamResource resource =
                    new org.springframework.core.io.InputStreamResource(is) {
                        @Override
                        public String getFilename() { return file.getOriginalFilename(); }
                        @Override
                        public long contentLength() { return len; }
                    };
            org.springframework.util.LinkedMultiValueMap<String, Object> parts =
                    new org.springframework.util.LinkedMultiValueMap<>();
            parts.add("file", resource);
            org.springframework.http.HttpHeaders headers = authHeaders(authHeader);
            headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
            org.springframework.http.HttpEntity<?> entity =
                    new org.springframework.http.HttpEntity<>(parts, headers);
            return restTemplate.postForObject(llmBaseUrl + "/api/v1/rag/kb/" + kbId + "/documents", entity, Object.class);
        } catch (java.io.IOException e) {
            throw new ChatServiceException("rag", "UPLOAD_IO_ERROR", "读取文件失败: " + e.getMessage(), e);
        }
    }

    // =========================== LLM 模型管理（chat-llm） ===========================

    /** GET /api/v1/llm/admin/providers - 提供商列表（chat-llm，apiKey 脱敏） */
    public Object listLlmProviders(String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers",
                org.springframework.http.HttpMethod.GET, entity, Object.class).getBody();
    }

    /** GET /api/v1/llm/admin/providers/types - 支持的调用类型（chat-llm） */
    public Object listLlmProviderTypes(String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers/types",
                org.springframework.http.HttpMethod.GET, entity, Object.class).getBody();
    }

    /** POST /api/v1/llm/admin/providers - 新增提供商（chat-llm） */
    public Object createLlmProvider(Map<String, Object> body, String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(body, authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers",
                org.springframework.http.HttpMethod.POST, entity, Object.class).getBody();
    }

    /** PUT /api/v1/llm/admin/providers/{id} - 更新提供商（chat-llm） */
    public Object updateLlmProvider(Long id, Map<String, Object> body, String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(body, authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers/" + id,
                org.springframework.http.HttpMethod.PUT, entity, Object.class).getBody();
    }

    /** DELETE /api/v1/llm/admin/providers/{id} - 删除提供商（chat-llm） */
    public Object deleteLlmProvider(Long id, String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers/" + id,
                org.springframework.http.HttpMethod.DELETE, entity, Object.class).getBody();
    }

    /** POST /api/v1/llm/admin/providers/reload - 全量重载（chat-llm） */
    public Object reloadLlmProviders(String authHeader) {
        org.springframework.http.HttpEntity<?> entity =
                new org.springframework.http.HttpEntity<>(authHeaders(authHeader));
        return restTemplate.exchange(llmBaseUrl + "/api/v1/llm/admin/providers/reload",
                org.springframework.http.HttpMethod.POST, entity, Object.class).getBody();
    }

    @Value("${app.core.base-urls:}")
    private String coreBaseUrlsExtra;

    private final RestTemplate restTemplate;
    private final java.util.List<String> coreUrls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger roundRobin = new java.util.concurrent.atomic.AtomicInteger(0);

    public CoreClient(@Autowired(required = false) RestTemplate restTemplate) {
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        coreUrls.add(coreBaseUrl);
        if (coreBaseUrlsExtra != null && !coreBaseUrlsExtra.isBlank()) {
            for (String url : coreBaseUrlsExtra.split(",")) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty() && !coreUrls.contains(trimmed)) {
                    coreUrls.add(trimmed);
                }
            }
        }
        log.info("[CoreClient] Core 服务地址: {}", coreUrls);
    }

    private String nextCoreUrl() {
        if (coreUrls.size() == 1) return coreUrls.get(0);
        int idx = Math.abs(roundRobin.getAndIncrement()) % coreUrls.size();
        return coreUrls.get(idx);
    }

    // ==================== 群聊 ====================

    public void chatProcess(Map<String, Object> payload) {
        post("/internal/chat/process", payload);
    }

    public void chatProcessWithFile(String reqId, Long userId, String question,
                                     String fileName, byte[] fileBytes, String mimeType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        payload.put("user_id", userId);
        payload.put("question", question);
        payload.put("file_name", fileName);
        payload.put("mime_type", mimeType);
        payload.put("file_data", Base64.getEncoder().encodeToString(fileBytes));
        post("/internal/chat/process-with-file", payload);
    }

    public void chatRegenerate(String reqId, Long userId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        payload.put("user_id", userId);
        post("/internal/chat/regenerate", payload);
    }

    public void chatStop(String reqId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        // 双 core 部署下 stop 必须广播到所有实例，否则可能打到非承载实例导致停止失效
        broadcast("/internal/chat/stop", payload);
    }

    // ==================== 消息查询 ====================

    public Object listMessages(Long userId) {
        return get("/internal/messages?user_id=" + userId);
    }

    public Object listRecentPrivate(Long userId) {
        return get("/internal/messages/recent?user_id=" + userId);
    }

    public Object searchPrivateMessages(Long userId, String keyword, int page, int size) {
        return get(String.format("/internal/messages/search?user_id=%d&keyword=%s&page=%d&size=%d",
                userId, keyword, page, size));
    }

    public Object getContextMessages(Long userId, Long msgId) {
        return get(String.format("/internal/messages/context?user_id=%d&msg_id=%d", userId, msgId));
    }

    public Object listAllMessages() {
        return get("/internal/messages/all");
    }

    public Object listQuestionsOnly() {
        return get("/internal/messages/questions");
    }

    public Object searchQuestions(String keyword) {
        return get("/internal/messages/search-all?q=" + keyword);
    }

    public Object getAnswerById(Long id) {
        return get("/internal/messages/" + id + "/answer");
    }

    public Object getMessageByReqId(String reqId) {
        return get("/internal/messages/by-req-id/" + reqId);
    }

    public Object insertMessage(Map<String, Object> message) {
        return post("/internal/messages/insert", message);
    }

    // ==================== 树洞 ====================

    public void treeHoleAsk(Long userId, String question, String reqId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", userId);
        payload.put("question", question);
        payload.put("mood", "");
        post("/internal/treehole/ask", payload);
    }

    public void treeHoleAskWithFile(Long userId, String question, String reqId,
                                     String fileName, byte[] fileBytes, String mimeType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", userId);
        payload.put("question", question);
        payload.put("mood", "");
        payload.put("file_name", fileName);
        payload.put("file_data", Base64.getEncoder().encodeToString(fileBytes));
        post("/internal/treehole/ask-with-file", payload);
    }

    public void treeHoleRegenerate(Long userId, String reqId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        payload.put("user_id", userId);
        post("/internal/treehole/regenerate", payload);
    }

    public void treeHoleStop(String reqId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("req_id", reqId);
        // 双 core 部署下 stop 必须广播到所有实例，否则可能打到非承载实例导致停止失效
        broadcast("/internal/treehole/stop", payload);
    }

    public Object treeHoleHistory(Long userId) {
        return get("/internal/treehole/history?user_id=" + userId);
    }

    public Object treeHoleRecent(Long userId) {
        return get("/internal/treehole/recent?user_id=" + userId);
    }

    public Object treeHoleSearch(Long userId, String keyword, int page, int size) {
        return get(String.format("/internal/treehole/search?user_id=%d&keyword=%s&page=%d&size=%d",
                userId, keyword, page, size));
    }

    public Object treeHoleContext(Long userId, Long msgId) {
        return get(String.format("/internal/treehole/context?user_id=%d&msg_id=%d", userId, msgId));
    }

    public Object insertTreeHole(Map<String, Object> payload) {
        return post("/internal/treehole/insert", payload);
    }

    // ==================== 辩论 ====================

    public void debateStart(Map<String, Object> payload) {
        post("/internal/debate/start", payload);
    }

    public Object debateRecords(Long userId) {
        return get("/internal/debate/records?user_id=" + userId);
    }

    public Object debateRecordsRecent(Long userId) {
        return get("/internal/debate/records/recent?user_id=" + userId);
    }

    public Object debateRecordById(Long id) {
        return get("/internal/debate/records/" + id);
    }

    public Object debateRecordsSearch(Long userId, String keyword, int page, int size) {
        return get(String.format("/internal/debate/records/search?user_id=%d&keyword=%s&page=%d&size=%d",
                userId, keyword, page, size));
    }

    public Object insertDebateRecord(Map<String, Object> payload) {
        return post("/internal/debate/records/insert", payload);
    }

    // ==================== 用户 ====================

    public Object getUserById(Long id) {
        return get("/internal/users/" + id);
    }

    public Object getUserByEmail(String email) {
        return get("/internal/users/email/" + email);
    }

    public Object insertUser(Map<String, Object> payload) {
        return post("/internal/users/insert", payload);
    }

    // ==================== 模型配置 ====================

    public Object listModels() {
        return get("/internal/models");
    }

    public Object getModelById(Long id) {
        return get("/internal/models/" + id);
    }

    public Object saveModel(Map<String, Object> payload) {
        return post("/internal/models/save", payload);
    }

    // ==================== 摘要 ====================

    public Object generateSummary(Long userId, String provider, String model) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("user_id", userId);
        payload.put("provider", provider);
        payload.put("model", model);
        return post("/internal/summary/generate", payload);
    }

    // ==================== 自动对话 ====================

    public void triggerAutoChat(String provider, String model) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("provider", provider);
        payload.put("model", model);
        post("/internal/auto-chat/trigger", payload);
    }

    public Object autoChatStatus() {
        return get("/internal/auto-chat/status");
    }

    public void stopAutoChat() {
        post("/internal/auto-chat/stop", new HashMap<>());
    }

    // ==================== 可观测性 ====================

    public Object getRecentTraces(int count) {
        return get("/internal/traces?n=" + count);
    }

    public Object searchTraces(String keyword) {
        return get("/internal/traces/search?keyword=" + (keyword == null ? "" : keyword));
    }

    public Object getErrorStats() {
        return get("/internal/errors");
    }

    // ==================== 知识图谱 ====================

    public Object getGraph(int limit, int minEntityWeight, int minRelationWeight) {
        return get("/internal/graph?limit=" + limit + "&minEntityWeight=" + minEntityWeight + "&minRelationWeight=" + minRelationWeight);
    }

    public Object searchGraph(String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        return get("/internal/graph/search?keyword=" + keyword + "&limit=" + limit + "&minEntityWeight=" + minEntityWeight + "&minRelationWeight=" + minRelationWeight);
    }

    public Object getGraphStats() {
        return get("/internal/graph/stats");
    }

    public Object importToGraph() {
        return post("/internal/graph/import", new HashMap<>());
    }

    public Object getImportStatus() {
        return get("/internal/graph/import/status");
    }

    // ==================== 内部方法 ====================

    /**
     * 广播 POST 到所有 core 实例（用于 stop 等需要命中承载实例的操作）
     * 任一实例成功即视为成功，全部失败才抛异常
     */
    private void broadcast(String path, Map<String, Object> payload) {
        Exception lastEx = null;
        int success = 0;
        for (String url : coreUrls) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
                restTemplate.postForEntity(url + path, entity, Object.class);
                success++;
            } catch (Exception e) {
                log.warn("[CoreClient] 广播 {} -> {} 失败: {}", path, url, e.getMessage());
                lastEx = e;
            }
        }
        if (success == 0) {
            log.error("[CoreClient] 广播 {} 所有 Core 实例均失败", path);
            throw new ChatServiceException("core", "ALL_INSTANCES_FAILED", "调用核心服务失败: " +
                    (lastEx != null ? lastEx.getMessage() : "无可用实例"), lastEx);
        }
    }

    private Object get(String path) {
        Exception lastEx = null;
        for (int i = 0; i < coreUrls.size(); i++) {
            String url = nextCoreUrl();
            try {
                ResponseEntity<Object> resp = restTemplate.getForEntity(url + path, Object.class);
                return resp.getBody();
            } catch (Exception e) {
                log.warn("[CoreClient] GET {} 从 {} 失败: {}", path, url, e.getMessage());
                lastEx = e;
            }
        }
        log.error("[CoreClient] GET {} 所有 Core 实例均失败", path);
        throw new ChatServiceException("core", "ALL_INSTANCES_FAILED", "调用核心服务失败: " +
                (lastEx != null ? lastEx.getMessage() : "无可用实例"), lastEx);
    }

    private Object post(String path, Map<String, Object> payload) {
        Exception lastEx = null;
        for (int i = 0; i < coreUrls.size(); i++) {
            String url = nextCoreUrl();
            try {
                log.info("[CoreClient] POST {} -> {} ", path, url);
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
                ResponseEntity<Object> resp = restTemplate.postForEntity(url + path, entity, Object.class);
                log.info("[CoreClient] POST {} -> {} 成功 status={}", path, url, resp.getStatusCode());
                return resp.getBody();
            } catch (Exception e) {
                log.warn("[CoreClient] POST {} 从 {} 失败: {}", path, url, e.getMessage());
                lastEx = e;
            }
        }
        log.error("[CoreClient] POST {} 所有 Core 实例均失败", path);
        throw new ChatServiceException("core", "ALL_INSTANCES_FAILED", "调用核心服务失败: " +
                (lastEx != null ? lastEx.getMessage() : "无可用实例"), lastEx);
    }
}
