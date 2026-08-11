package com.example.chat.controller;

import com.example.chat.client.CoreClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库管理 Controller（代理转发到 chat-core，需传递 Authorization header）
 */
@Tag(name = "知识库管理", description = "RAG 知识库的创建、文档上传、查询和删除")
@RestController
@RequestMapping("/api/v1/rag")
public class KnowledgeBaseController {

    private final CoreClient coreClient;

    public KnowledgeBaseController(CoreClient coreClient) {
        this.coreClient = coreClient;
    }

    private String auth(HttpServletRequest request) {
        return request.getHeader("Authorization");
    }

    @Operation(summary = "获取知识库列表")
    @GetMapping("/kb")
    public ResponseEntity<?> listKnowledgeBases(HttpServletRequest request) {
        return ResponseEntity.ok(coreClient.listKnowledgeBases(auth(request)));
    }

    @Operation(summary = "创建知识库")
    @PostMapping("/kb")
    public ResponseEntity<?> createKnowledgeBase(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest request) {
        return ResponseEntity.ok(coreClient.createKnowledgeBase(body, auth(request)));
    }

    @Operation(summary = "删除知识库")
    @DeleteMapping("/kb/{knowledgeBaseId}")
    public ResponseEntity<?> deleteKnowledgeBase(@PathVariable Long knowledgeBaseId,
                                                  HttpServletRequest request) {
        return ResponseEntity.ok(coreClient.deleteKnowledgeBase(knowledgeBaseId, auth(request)));
    }

    @Operation(summary = "获取文档列表")
    @GetMapping("/kb/{knowledgeBaseId}/docs")
    public ResponseEntity<?> listDocuments(@PathVariable Long knowledgeBaseId,
                                           HttpServletRequest request) {
        return ResponseEntity.ok(coreClient.listDocuments(knowledgeBaseId, auth(request)));
    }

    @Operation(summary = "上传文档到知识库")
    @PostMapping("/kb/{knowledgeBaseId}/docs")
    public ResponseEntity<?> uploadDocuments(@PathVariable Long knowledgeBaseId,
                                              @RequestParam("files") List<MultipartFile> files,
                                              HttpServletRequest request) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择文件"));
        }
        String authHeader = auth(request);
        for (MultipartFile file : files) {
            coreClient.uploadDocument(knowledgeBaseId, file, authHeader);
        }
        return ResponseEntity.ok(Map.of("success", true, "count", files.size()));
    }

    @Operation(summary = "删除文档")
    @DeleteMapping("/kb/{knowledgeBaseId}/docs/{documentId}")
    public ResponseEntity<?> deleteDocument(@PathVariable Long knowledgeBaseId,
                                            @PathVariable Long documentId,
                                            HttpServletRequest request) {
        return ResponseEntity.ok(coreClient.deleteDocument(documentId, auth(request)));
    }
}
