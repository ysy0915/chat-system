package com.example.chat.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 知识图谱客户端 —— 通过内部 HTTP 调用 chat-llm 的 /internal/graph/* 端点。
 *
 * <p>知识图谱运行时已迁移至 chat-llm（2026-08），chat-core 不再直连 Neo4j，
 * 统一通过本客户端访问：对话完成后异步触发三元组抽取、图谱查询/搜索/统计/批量导入。
 * chat-llm 不可用或服务未启用时全部安全降级（返回空结构），不阻塞主流程。</p>
 */
@Component
public class GraphClient {

    private static final Logger log = LoggerFactory.getLogger(GraphClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final ExecutorService asyncExecutor = new ThreadPoolExecutor(
            1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy());

    @Value("${app.llm-service.base-url:http://127.0.0.1:9095}")
    private String baseUrl;

    public GraphClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ──────────── 异步三元组抽取（fire-and-forget） ─────────────────

    /**
     * 对话完成后异步触发知识三元组抽取（不阻塞业务线程，失败静默降级）。
     */
    public void extractAndSaveAsync(Long messageId, String question, String answer, String source) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) return;
        asyncExecutor.submit(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("messageId", messageId);
                body.put("question", question);
                body.put("answer", answer);
                body.put("source", source != null ? source : "chat");
                postJson("/internal/graph/extract", body);
            } catch (Exception e) {
                log.debug("[GraphClient] 触发知识抽取失败 msg={}: {}", messageId, e.getMessage());
            }
        });
    }

    // ──────────── 图谱查询 ─────────────────────────────────────────

    /** 获取知识图谱（节点 + 关系边），失败返回空结构。 */
    public Map<String, Object> getGraph(int limit, int minEntityWeight, int minRelationWeight) {
        try {
            Map<String, Object> r = getJson("/internal/graph?limit=" + limit + "&minEntityWeight=" + minEntityWeight
                    + "&minRelationWeight=" + minRelationWeight);
            return r != null ? r : emptyGraph();
        } catch (Exception e) {
            log.warn("[GraphClient] getGraph 异常: {}", e.getMessage());
            return emptyGraph();
        }
    }

    /** 按关键词搜索实体，失败返回空结构。 */
    public Map<String, Object> searchEntities(String keyword, int limit, int minEntityWeight, int minRelationWeight) {
        try {
            Map<String, Object> r = getJson("/internal/graph/search?keyword=" + urlEncode(keyword)
                    + "&limit=" + limit + "&minEntityWeight=" + minEntityWeight
                    + "&minRelationWeight=" + minRelationWeight);
            return r != null ? r : emptyGraph();
        } catch (Exception e) {
            log.warn("[GraphClient] searchEntities 异常: {}", e.getMessage());
            return emptyGraph();
        }
    }

    /** 图谱统计（实体数 / 关系数），失败返回 0。 */
    public Map<String, Object> getStats() {
        try {
            Map<String, Object> r = getJson("/internal/graph/stats");
            return r != null ? r : Map.of("entityCount", 0, "relationCount", 0);
        } catch (Exception e) {
            log.warn("[GraphClient] getStats 异常: {}", e.getMessage());
            return Map.of("entityCount", 0, "relationCount", 0);
        }
    }

    /** 触发批量导入，失败返回 started=false。 */
    public Map<String, Object> startBatchImport() {
        try {
            Map<String, Object> r = postJson("/internal/graph/import", Map.of());
            return r != null ? r : Map.of("started", false, "importing", false);
        } catch (Exception e) {
            log.warn("[GraphClient] startBatchImport 异常: {}", e.getMessage());
            return Map.of("started", false, "importing", false);
        }
    }

    /** 批量导入状态，失败返回 importing=false。 */
    public Map<String, Object> getImportStatus() {
        try {
            Map<String, Object> r = getJson("/internal/graph/import/status");
            return r != null ? r : Map.of("importing", false);
        } catch (Exception e) {
            log.warn("[GraphClient] getImportStatus 异常: {}", e.getMessage());
            return Map.of("importing", false);
        }
    }

    // ──────────── 内部方法 ─────────────────────────────────────────

    private Map<String, Object> emptyGraph() {
        return Map.of("nodes", List.of(), "edges", List.of());
    }

    private String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            log.warn("[GraphClient] {} HTTP {}", path, resp.statusCode());
            return Map.of();
        }
        return objectMapper.readValue(resp.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            log.warn("[GraphClient] {} HTTP {}", path, resp.statusCode());
            return Map.of();
        }
        return objectMapper.readValue(resp.body(), Map.class);
    }
}
