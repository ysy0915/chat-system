package com.example.chat.llm.controller;

import com.example.chat.llm.service.KnowledgeGraphService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱内部 API —— 知识图谱运行时已迁移至 chat-llm（2026-08）。
 *
 * <p>chat-core 经 {@code GraphClient} 跨进程调用本控制器：对话完成后异步触发三元组抽取、
 * 图谱查询 / 搜索 / 统计 / 批量导入。响应格式与迁移前 chat-core 的
 * {@code InternalApiController} 完全一致，chat-web 透传无需改动。</p>
 *
 * <p>服务未启用（{@code app.knowledge-graph.enabled=false}）时返回空结构，调用方安全降级。</p>
 */
@Tag(name = "知识图谱内部API", description = "供 chat-core 调用的知识图谱接口（Neo4j 运行时）")
@RestController
@ConditionalOnProperty(name = "app.knowledge-graph.enabled", havingValue = "true")
@RequestMapping("/internal/graph")
public class KnowledgeGraphController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphController.class);

    private final KnowledgeGraphService knowledgeGraphService;

    public KnowledgeGraphController(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    @Operation(summary = "获取知识图谱", description = "获取知识图谱的全部节点和关系边")
    @GetMapping
    public ResponseEntity<?> getGraph(
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "minEntityWeight", defaultValue = "1") int minEntityWeight,
            @RequestParam(value = "minRelationWeight", defaultValue = "1") int minRelationWeight) {
        try {
            return ResponseEntity.ok(knowledgeGraphService.getGraph(limit, minEntityWeight, minRelationWeight));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] getGraph 异常: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of()));
        }
    }

    @Operation(summary = "搜索知识图谱", description = "按关键词搜索实体及其邻居节点")
    @GetMapping("/search")
    public ResponseEntity<?> searchGraph(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "limit", defaultValue = "30") int limit,
            @RequestParam(value = "minEntityWeight", defaultValue = "1") int minEntityWeight,
            @RequestParam(value = "minRelationWeight", defaultValue = "1") int minRelationWeight) {
        try {
            return ResponseEntity.ok(knowledgeGraphService.searchEntities(keyword, limit, minEntityWeight, minRelationWeight));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] searchEntities 异常: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("nodes", List.of(), "edges", List.of()));
        }
    }

    @Operation(summary = "知识图谱统计", description = "获取知识图谱的实体数和关系数统计")
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        try {
            return ResponseEntity.ok(knowledgeGraphService.getStats());
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] getStats 异常: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("entityCount", 0, "relationCount", 0));
        }
    }

    @Operation(summary = "批量导入知识图谱", description = "触发批量从数据库导入数据到知识图谱")
    @PostMapping("/import")
    public ResponseEntity<?> importToGraph() {
        boolean started = knowledgeGraphService.startBatchImport();
        return ResponseEntity.ok(Map.of(
                "started", started,
                "importing", knowledgeGraphService.isImporting()
        ));
    }

    @Operation(summary = "导入状态查询", description = "查询知识图谱批量导入的进度和状态")
    @GetMapping("/import/status")
    public ResponseEntity<?> getImportStatus() {
        return ResponseEntity.ok(Map.of(
                "importing", knowledgeGraphService.isImporting(),
                "stats", knowledgeGraphService.getStats()
        ));
    }

    @Operation(summary = "异步触发三元组抽取", description = "对话完成后异步抽取知识三元组写入 Neo4j（fire-and-forget）")
    @PostMapping("/extract")
    public ResponseEntity<?> extract(@RequestBody Map<String, Object> req) {
        Long messageId = req.get("messageId") != null ? ((Number) req.get("messageId")).longValue() : null;
        String question = req.get("question") != null ? String.valueOf(req.get("question")) : null;
        String answer = req.get("answer") != null ? String.valueOf(req.get("answer")) : null;
        String source = req.get("source") != null ? String.valueOf(req.get("source")) : "chat";
        knowledgeGraphService.extractAndSaveAsync(messageId, question, answer, source);
        return ResponseEntity.ok(Map.of("accepted", true));
    }
}
