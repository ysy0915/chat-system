package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识图谱 Controller
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final CoreClient coreClient;

    public GraphController(CoreClient coreClient) {
        this.coreClient = coreClient;
    }

    @GetMapping
    public ResponseEntity<?> getGraph(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ResponseEntity.ok(coreClient.getGraph(limit));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchGraph(@RequestParam("keyword") String keyword,
                                          @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ResponseEntity.ok(coreClient.searchGraph(keyword, limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(coreClient.getGraphStats());
    }

    @PostMapping("/import")
    public ResponseEntity<?> importToGraph() {
        return ResponseEntity.ok(coreClient.importToGraph());
    }

    @GetMapping("/import/status")
    public ResponseEntity<?> getImportStatus() {
        return ResponseEntity.ok(coreClient.getImportStatus());
    }
}
