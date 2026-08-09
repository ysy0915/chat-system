package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 知识图谱 Controller
 */
@Tag(name = "知识图谱", description = "消息三元组提取、图谱查询、批量导入")
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final CoreClient coreClient;

    public GraphController(CoreClient coreClient) {
        this.coreClient = coreClient;
    }

    @Operation(summary = "获取知识图谱", description = "返回图谱节点和关系数据，默认 100 条")
    @GetMapping
    public ResponseEntity<?> getGraph(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(coreClient.getGraph(limit));
    }

    @Operation(summary = "搜索知识图谱", description = "按关键词搜索实体及其邻居节点")
    @GetMapping("/search")
    public ResponseEntity<?> searchGraph(@RequestParam("keyword") String keyword,
                                          @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ResponseEntity.ok(coreClient.searchGraph(keyword, limit));
    }

    @Operation(summary = "图谱统计", description = "获取节点数、关系数等统计信息")
    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(coreClient.getGraphStats());
    }

    @Operation(summary = "触发批量导入", description = "启动知识图谱批量导入任务")
    @PostMapping("/import")
    public ResponseEntity<?> importToGraph() {
        return ResponseEntity.ok(coreClient.importToGraph());
    }

    @Operation(summary = "查询导入状态", description = "返回当前批量导入任务的进度状态")
    @GetMapping("/import/status")
    public ResponseEntity<?> getImportStatus() {
        return ResponseEntity.ok(coreClient.getImportStatus());
    }
}
