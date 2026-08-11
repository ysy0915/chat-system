package com.example.chat.llm.rag.controller;

import com.example.chat.llm.rag.RagService;
import com.example.chat.llm.rag.RagService.IngestResult;
import com.example.chat.llm.rag.RagService.RagResult;
import com.example.chat.llm.rag.RagService.RetrievedDoc;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RAG REST API — 检索增强生成 + 多数据源多模型切换。
 */
@Tag(name = "RAG API", description = "检索增强生成 (Retrieval-Augmented Generation) — 多数据源")
@RestController
@RequestMapping("/api/v1/llm/rag")
@ConditionalOnProperty(name = "rag.enabled", havingValue = "true")
public class RAGController {

    private final RagService ragService;

    public RAGController(RagService ragService) {
        this.ragService = ragService;
    }

    // ──────────── RAG 一键调用 ────────────────────────────

    @Operation(summary = "RAG 一键调用 (检索+生成)",
            description = "自动检索知识库 + LLM 生成回复。支持 dataSource 切换数据源。")
    @PostMapping("/invoke")
    public RagResult ragInvoke(@RequestBody Map<String, Object> req) {
        // 数据源路由
        String dataSource = (String) req.getOrDefault("dataSource", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) req.get("messages");
        Double temperature = num(req.get("temperature"));
        Integer maxTokens  = toInt(req.get("maxTokens"));
        String systemPrompt = (String) req.get("systemPrompt");
        String query = (String) req.getOrDefault("query", extractLastUserContent(messages));
        Integer topK = toIntObj(req.get("topK"));
        Float   threshold = req.get("scoreThreshold") != null
                ? ((Number) req.get("scoreThreshold")).floatValue() : null;

        return ragService.ragInvoke(dataSource, messages,
                temperature, maxTokens, systemPrompt,
                query, topK, threshold);
    }

    // ──────────── 纯检索 ──────────────────────────────────

    @Operation(summary = "仅检索文档", description = "只检索相似文档片段，不调用 LLM。支持 dataSource 切换。")
    @PostMapping("/retrieve")
    public Map<String, Object> retrieve(@RequestBody Map<String, Object> req) {
        String dataSource = (String) req.getOrDefault("dataSource", null);
        String query = (String) req.get("query");
        int topK = req.get("topK") != null ? ((Number) req.get("topK")).intValue() : 5;
        float threshold = req.get("scoreThreshold") != null
                ? ((Number) req.get("scoreThreshold")).floatValue() : 0.5f;

        List<RetrievedDoc> docs = ragService.retrieve(dataSource, query, topK, threshold);
        return Map.of("success", true, "results", docs, "total", docs.size());
    }

    // ──────────── 文档入库 ────────────────────────────────

    @Operation(summary = "文档入库", description = "上传文档 (PDF/DOCX/TXT) 并入库到指定数据源的向量库")
    @PostMapping(value = "/ingest", consumes = "multipart/form-data")
    public IngestResult ingest(
            @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "数据源名称 (不传用默认)") @RequestParam(required = false) String dataSource,
            @Parameter(description = "分块大小") @RequestParam(defaultValue = "500") int chunkSize,
            @Parameter(description = "分块重叠") @RequestParam(defaultValue = "50") int chunkOverlap) {
        try {
            return ragService.ingest(dataSource,
                    file.getBytes(), file.getContentType(),
                    file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown",
                    chunkSize, chunkOverlap, Map.of(), List.of());
        } catch (IOException e) {
            return IngestResult.fail("文件读取失败: " + e.getMessage());
        }
    }

    // ──────────── 删除文档 ────────────────────────────────

    @Operation(summary = "删除文档", description = "从指定数据源删除文档的所有片段")
    @DeleteMapping("/document/{docId}")
    public Map<String, Object> deleteDocument(
            @PathVariable String docId,
            @Parameter(description = "数据源名称") @RequestParam(required = false) String dataSource) {
        ragService.deleteDocument(dataSource, docId);
        return Map.of("success", true, "docId", docId);
    }

    // ──────────── 数据源管理 ──────────────────────────────

    @Operation(summary = "列出所有数据源", description = "查看已注册的 RAG 数据源及其绑定信息")
    @GetMapping("/datasources")
    public List<Map<String, Object>> listDataSources() {
        return ragService.listDataSources();
    }

    // ──────────── 工具方法 ────────────────────────────────

    private String extractLastUserContent(List<Map<String, Object>> messages) {
        if (messages == null) return "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return (String) messages.get(i).get("content");
            }
        }
        return "";
    }

    private static Double num(Object v) {
        return v != null ? ((Number) v).doubleValue() : null;
    }

    private static Integer toInt(Object v) {
        return v != null ? ((Number) v).intValue() : null;
    }

    private static Integer toIntObj(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }
}
