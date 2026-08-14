package com.example.chat.llm.rag.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>纯内存 RAG 知识库仓储（RAGRepository SPI）</h2>
 *
 * <p>standalone 模式（<code>app.rag.backend=memory</code>）下替代 MyBatis Mapper，
 * 用 <code>ConcurrentHashMap</code> 模拟 <code>rag_knowledge_bases</code> /
 * <code>rag_documents</code> 两张表。数据仅存活于进程内、重启即失。</p>
 *
 * <p>DB 模式下 MyBatis 代理生效，本实现不注册（backend 开关互斥），无冲突。</p>
 */
@Component
@ConditionalOnProperty(name = "app.rag.backend", havingValue = "memory")
public class InMemoryRAGRepository implements RAGRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRAGRepository.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<Long, KnowledgeBase> knowledgeBases = new ConcurrentHashMap<>();
    private final Map<Long, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final AtomicLong kbSeq = new AtomicLong(1);
    private final AtomicLong docSeq = new AtomicLong(1);

    public InMemoryRAGRepository() {
        log.info("[MemoryRAG] 纯内存 RAG 知识库仓储已启用（进程内存储，重启即失）");
    }

    // ============ 知识库管理 ============

    @Override
    public List<KnowledgeBase> findAllKnowledgeBases() {
        List<KnowledgeBase> list = new ArrayList<>(knowledgeBases.values());
        list.sort(Comparator.comparing((KnowledgeBase kb) -> kb.id).reversed());
        return list;
    }

    @Override
    public KnowledgeBase findKnowledgeBaseById(Long id) {
        return id == null ? null : knowledgeBases.get(id);
    }

    @Override
    public int insertKnowledgeBase(KnowledgeBase kb) {
        if (kb.id == null) {
            kb.id = kbSeq.getAndIncrement();
        }
        if (kb.createdAt == null) {
            kb.createdAt = LocalDateTime.now().format(TS);
        }
        knowledgeBases.put(kb.id, kb);
        return 1;
    }

    @Override
    public int deleteKnowledgeBase(Long id) {
        KnowledgeBase removed = knowledgeBases.remove(id);
        if (removed != null) {
            documents.entrySet().removeIf(e -> id.equals(e.getValue().knowledgeBaseId));
            return 1;
        }
        return 0;
    }

    @Override
    public int updateKnowledgeBaseStats(Long id, int docCount, long chunks) {
        KnowledgeBase kb = knowledgeBases.get(id);
        if (kb != null) {
            kb.documentCount = docCount;
            kb.totalChunks = chunks;
            return 1;
        }
        return 0;
    }

    // ============ 文档管理 ============

    @Override
    public List<KnowledgeDocument> findDocumentsByKbId(Long kbId) {
        List<KnowledgeDocument> list = documents.values().stream()
                .filter(d -> kbId.equals(d.knowledgeBaseId))
                .toList();
        List<KnowledgeDocument> sorted = new ArrayList<>(list);
        sorted.sort(Comparator.comparing((KnowledgeDocument d) -> d.id).reversed());
        return sorted;
    }

    @Override
    public int insertDocument(KnowledgeDocument doc) {
        if (doc.id == null) {
            doc.id = docSeq.getAndIncrement();
        }
        if (doc.createdAt == null) {
            doc.createdAt = LocalDateTime.now().format(TS);
        }
        documents.put(doc.id, doc);
        return 1;
    }

    @Override
    public int updateDocumentStatus(Long id, String status, String errorMessage, int chunkCount) {
        KnowledgeDocument doc = documents.get(id);
        if (doc != null) {
            doc.status = status;
            doc.errorMessage = errorMessage;
            doc.chunkCount = chunkCount;
            return 1;
        }
        return 0;
    }

    @Override
    public int deleteDocument(Long id) {
        return documents.remove(id) != null ? 1 : 0;
    }

    @Override
    public int countDocumentsByKbId(Long kbId) {
        return (int) documents.values().stream()
                .filter(d -> kbId.equals(d.knowledgeBaseId))
                .count();
    }

    @Override
    public long sumChunksByKbId(Long kbId) {
        return documents.values().stream()
                .filter(d -> kbId.equals(d.knowledgeBaseId) && "done".equals(d.status))
                .mapToLong(d -> d.chunkCount)
                .sum();
    }
}
