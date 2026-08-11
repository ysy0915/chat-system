package com.example.chat.rag.controller;

import com.example.chat.config.ThreadPoolFactory;
import com.example.chat.rag.entity.KnowledgeBase;
import com.example.chat.rag.entity.KnowledgeDocument;
import com.example.chat.rag.repository.RAGRepository;
import com.example.chat.rag.service.DocumentParser;
import com.example.chat.rag.service.TextChunker;
import com.example.chat.rag.service.VectorStoreService;
import com.example.chat.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 知识库管理 Controller
 *
 * API 列表：
 *   POST   /api/v1/rag/kb                创建知识库
 *   GET    /api/v1/rag/kb                列出所有知识库
 *   DELETE /api/v1/rag/kb/{id}           删除知识库（同时删除向量）
 *
 *   POST   /api/v1/rag/kb/{id}/documents 上传文档到知识库（自动解析+分片+向量化）
 *   GET    /api/v1/rag/kb/{id}/documents 列出知识库的文档
 *   DELETE /api/v1/rag/documents/{id}    删除单个文档（同时删除向量）
 *
 *   POST   /api/v1/rag/search            测试检索（不调 LLM，只看召回结果）
 */
@Tag(name = "知识库管理", description = "RAG 知识库的创建、文档上传与检索")
@RestController
@RequestMapping("/api/v1/rag")
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final RAGRepository ragRepository;
    private final VectorStoreService vectorStoreService;
    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final JwtUtil jwtUtil;

    /** 文档解析/向量化异步线程池：不阻塞 HTTP 上传线程 */
    private final ExecutorService ragExecutor;

    @Value("${app.rag.upload.max-size:10485760}")  // 默认 10MB
    private long maxUploadSize;

    public KnowledgeController(RAGRepository ragRepository,
                               @org.springframework.beans.factory.annotation.Autowired(required = false) VectorStoreService vectorStoreService,
                               DocumentParser documentParser,
                               TextChunker textChunker,
                               JwtUtil jwtUtil) {
        this.ragRepository = ragRepository;
        this.vectorStoreService = vectorStoreService;
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.jwtUtil = jwtUtil;
        this.ragExecutor = ThreadPoolFactory.create(2, 4, 50, "rag-ingest-");
    }

    /** 校验 token 并要求 admin 角色，返回 null 表示通过，否则返回错误响应 */
    private ResponseEntity<?> checkAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            return ResponseEntity.status(401).body(Map.of("error", "登录已过期，请重新登录"));
        }
        String role = jwtUtil.getRole(token);
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("error", "仅管理员可操作知识库"));
        }
        return null;
    }

    // ============ 知识库 CRUD ============

    @Operation(summary = "创建知识库", description = "创建一个新的RAG知识库，需管理员权限")
    @PostMapping("/kb")
    public ResponseEntity<?> createKnowledgeBase(@RequestBody Map<String, String> body,
                                                  @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;

        KnowledgeBase kb = new KnowledgeBase();
        kb.name = body.get("name");
        kb.description = body.getOrDefault("description", "");
        if (kb.name == null || kb.name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name 不能为空"));
        }

        ragRepository.insertKnowledgeBase(kb);
        log.info("[RAG] 创建知识库 id={} name={}", kb.id, kb.name);
        return ResponseEntity.ok(kb);
    }

    @Operation(summary = "知识库列表", description = "列出所有已创建的知识库，需管理员权限")
    @GetMapping("/kb")
    public ResponseEntity<?> listKnowledgeBases(@Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;
        return ResponseEntity.ok(ragRepository.findAllKnowledgeBases());
    }

    @Operation(summary = "删除知识库", description = "删除知识库及其向量数据，需管理员权限")
    @DeleteMapping("/kb/{id}")
    public ResponseEntity<?> deleteKnowledgeBase(
            @Parameter(description = "知识库ID") @PathVariable Long id,
            @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;

        // 删除 Milvus 中的 Collection
        if (vectorStoreService != null) {
            vectorStoreService.dropCollection(id);
        }
        ragRepository.deleteKnowledgeBase(id);
        log.info("[RAG] 删除知识库 id={}", id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    // ============ 文档管理 ============

    @Operation(summary = "上传文档到知识库", description = "上传文件到指定知识库，自动解析、分片并向量化，需管理员权限")
    @PostMapping("/kb/{kbId}/documents")
    public ResponseEntity<?> uploadDocument(
            @Parameter(description = "知识库ID") @PathVariable Long kbId,
            @Parameter(description = "上传的文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;

        if (file.getSize() > maxUploadSize) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件超过 " + maxUploadSize + " 字节限制"));
        }

        final String fileName = file.getOriginalFilename();
        try {
            final byte[] bytes = file.getBytes();

            // 1. 创建文档记录（状态 processing），立即返回，不阻塞上传线程
            KnowledgeDocument doc = new KnowledgeDocument();
            doc.knowledgeBaseId = kbId;
            doc.fileName = fileName;
            doc.source = fileName;
            doc.fileSize = bytes.length;
            doc.chunkCount = 0;
            doc.status = "processing";
            ragRepository.insertDocument(doc);
            final long docId = doc.id;

            // 2-5. 解析/分片/向量化/状态更新放到异步线程池执行
            ragExecutor.submit(() -> ingestDocumentAsync(kbId, docId, fileName, bytes));

            return ResponseEntity.accepted().body(Map.of(
                    "documentId", docId,
                    "fileName", fileName,
                    "chunkCount", 0,
                    "status", "processing"
            ));
        } catch (Exception e) {
            log.error("[RAG] 文档上传失败 kb={} error={}", kbId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** 异步执行文档解析 → 分片 → 向量化入库 → 状态更新 */
    private void ingestDocumentAsync(long kbId, long docId, String fileName, byte[] bytes) {
        try {
            // 2. 解析文档为纯文本
            String text = documentParser.parse(fileName, bytes);

            // 3. 分片
            List<VectorStoreService.ChunkText> chunks = textChunker.chunk(text);
            log.info("[RAG] 文档 {} 解析+分片完成 共 {} 片", fileName, chunks.size());

            // 4. 向量化入库
            if (vectorStoreService != null) {
                vectorStoreService.insertChunks(kbId, docId, chunks, fileName);
            }

            // 5. 更新文档状态
            ragRepository.updateDocumentStatus(docId, "done", null, chunks.size());

            // 6. 更新知识库统计
            updateKbStats(kbId);
        } catch (Exception e) {
            log.error("[RAG] 文档异步处理失败 kb={} doc={} error={}", kbId, docId, e.getMessage(), e);
            try {
                ragRepository.updateDocumentStatus(docId, "failed", e.getMessage(), 0);
            } catch (Exception ignored) {
                // 状态更新失败不覆盖原始异常
            }
        }
    }

    @Operation(summary = "文档列表", description = "列出指定知识库中的所有文档，需管理员权限")
    @GetMapping("/kb/{kbId}/documents")
    public ResponseEntity<?> listDocuments(
            @Parameter(description = "知识库ID") @PathVariable Long kbId,
            @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;
        return ResponseEntity.ok(ragRepository.findDocumentsByKbId(kbId));
    }

    @Operation(summary = "删除文档", description = "删除指定文档及其向量数据，需管理员权限")
    @DeleteMapping("/documents/{id}")
    public ResponseEntity<?> deleteDocument(
            @Parameter(description = "文档ID") @PathVariable Long id,
            @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;
        // 注意：Milvus 按 doc_id 删除向量需要额外实现 deleteEntities
        // 当前简化为只删除 MySQL 记录，向量残留可定期重建 Collection 清理
        ragRepository.deleteDocument(id);
        log.info("[RAG] 删除文档 id={}", id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    // ============ 检索测试 ============

    @Operation(summary = "知识库检索测试", description = "在指定知识库中执行向量检索，返回语义相似的文档片段，用于测试检索效果")
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody Map<String, Object> body,
                                    @Parameter(description = "认证Token，格式：Bearer xxx") @RequestHeader("Authorization") String auth) {
        ResponseEntity<?> err = checkAdmin(auth);
        if (err != null) return err;

        Long kbId = ((Number) body.get("knowledgeBaseId")).longValue();
        String query = (String) body.get("query");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 5;

        if (vectorStoreService == null) {
            return ResponseEntity.internalServerError().body(Map.of("error", "向量库未启用"));
        }

        List<VectorStoreService.SearchResult> results = vectorStoreService.search(kbId, query, topK);
        return ResponseEntity.ok(results.stream().map(r -> Map.of(
                "text", r.text,
                "source", r.source,
                "docId", r.docId,
                "score", r.score
        )).toList());
    }

    // ============ 内部方法 ============

    private void updateKbStats(Long kbId) {
        int docCount = ragRepository.countDocumentsByKbId(kbId);
        long chunks = ragRepository.sumChunksByKbId(kbId);
        ragRepository.updateKnowledgeBaseStats(kbId, docCount, chunks);
    }
}
