package com.example.chat.llm.rag.legacy;

import com.example.chat.storage.VectorStore;
import com.example.chat.storage.VectorStore.VectorHit;
import com.example.chat.storage.VectorStore.VectorRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <h2>纯内存向量存储（standalone 模式，零基础设施依赖）</h2>
 *
 * <p>实现 {@link VectorStore}（存储平台化 SPI）与 {@link VectorStoreLegacy}（kbId 语义），
 * 在 JVM 内以 <code>ConcurrentHashMap&lt;collection, List&lt;MemChunk&gt;&gt;</code> 保存
 * 文本分片 + 稠密向量，检索时用<b>余弦相似度</b>暴力计算 TopK。</p>
 *
 * <p>由 <code>app.rag.backend=memory</code> 开启（standalone profile 已配置），
 * 数据仅存活于进程内、重启即失，适合开发/演示/轻量自托管场景。</p>
 *
 * <p>向量化仍通过 {@link LegacyEmbeddingService} 调用外部 Embedding API
 * （与 LLM API 同类，属外部 API 依赖而非基础设施依赖）。</p>
 */
@Service
@ConditionalOnProperty(name = "app.rag.backend", havingValue = "memory")
public class InMemoryVectorStoreService implements VectorStore, VectorStoreLegacy {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStoreService.class);

    /** collection → 分片列表 */
    private final Map<String, List<MemChunk>> collections = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private final LegacyEmbeddingService embeddingService;

    @Value("${app.rag.milvus.collection-prefix:kb_}")
    private String collectionPrefix;

    public InMemoryVectorStoreService(LegacyEmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        log.info("[MemoryVectorStore] 纯内存向量库已启用（进程内存储，重启即失）");
    }

    // ── VectorStoreLegacy（kbId 语义） ─────────────────────────

    @Override
    public void ensureCollection(Long knowledgeBaseId) {
        // 惰性创建：内存 Map 无需预建
        String name = getCollectionName(knowledgeBaseId);
        collections.computeIfAbsent(name, k -> new ArrayList<>());
    }

    @Override
    public void insertChunks(Long knowledgeBaseId, Long docId, List<ChunkText> chunks, String source) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String collectionName = getCollectionName(knowledgeBaseId);
        try {
            List<String> texts = chunks.stream().map(c -> c.text).toList();
            List<float[]> vectors = embeddingService.embedBatch(texts);
            if (vectors.size() != chunks.size()) {
                log.warn("[MemoryVectorStore] 向量数 {} 与分片数 {} 不一致，丢弃入库", vectors.size(), chunks.size());
                return;
            }
            List<MemChunk> bucket = collections.computeIfAbsent(collectionName, k -> new ArrayList<>());
            synchronized (bucket) {
                for (int i = 0; i < chunks.size(); i++) {
                    bucket.add(new MemChunk(idSeq.getAndIncrement(),
                            docId != null ? docId : -1L,
                            chunks.get(i).chunkIndex,
                            chunks.get(i).text,
                            source,
                            chunks.get(i).page,
                            vectors.get(i)));
                }
            }
            log.info("[MemoryVectorStore] 插入 {} 条分片到 {}（累计 {}）",
                    chunks.size(), collectionName, bucket.size());
        } catch (Exception e) {
            log.warn("[MemoryVectorStore] 入库失败 collection={} error={}", collectionName, e.getMessage());
        }
    }

    @Override
    public List<SearchResult> search(Long knowledgeBaseId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] queryVec = embeddingService.embed(query);
            return searchByVector(getCollectionName(knowledgeBaseId), queryVec, topK);
        } catch (Exception e) {
            log.warn("[MemoryVectorStore] 检索失败 kbId={} error={}", knowledgeBaseId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void dropCollection(Long knowledgeBaseId) {
        String name = getCollectionName(knowledgeBaseId);
        List<MemChunk> removed = collections.remove(name);
        log.info("[MemoryVectorStore] 删除 Collection: {}（{} 条）", name, removed == null ? 0 : removed.size());
    }

    @Override
    public String name() {
        return "memory";
    }

    // ── VectorStore SPI 适配（通用 collection 语义） ──────────────

    @Override
    public void ensureCollection(String collection, int dimension) {
        ensureCollection(kbIdOfCollection(collection));
    }

    @Override
    public void insert(String collection, List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Long kbId = kbIdOfCollection(collection);
        String source = records.get(0).source;
        List<ChunkText> chunks = new ArrayList<>();
        int idx = 0;
        for (VectorRecord r : records) {
            chunks.add(new ChunkText(r.text, idx++));
        }
        insertChunks(kbId, -1L, chunks, source);
    }

    @Override
    public List<VectorHit> search(String collection, float[] queryVector, int topK) {
        List<SearchResult> results = searchByVector(collection, queryVector, topK);
        List<VectorHit> hits = new ArrayList<>();
        for (SearchResult r : results) {
            hits.add(new VectorHit(r.text, r.source, r.score));
        }
        return hits;
    }

    @Override
    public void dropCollection(String collection) {
        dropCollection(kbIdOfCollection(collection));
    }

    // ── 内部实现 ───────────────────────────────────────────────

    private List<SearchResult> searchByVector(String collectionName, float[] queryVec, int topK) {
        List<MemChunk> bucket = collections.get(collectionName);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> scored = new ArrayList<>();
        synchronized (bucket) {
            for (MemChunk c : bucket) {
                float cos = cosine(queryVec, c.vector);
                if (!Float.isNaN(cos)) {
                    scored.add(new ScoredChunk(c, cos));
                }
            }
        }
        scored.sort(Comparator.comparingDouble((ScoredChunk s) -> s.score).reversed());
        int n = Math.min(topK > 0 ? topK : 5, scored.size());
        List<SearchResult> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ScoredChunk s = scored.get(i);
            results.add(new SearchResult(s.chunk.text, s.chunk.source, s.chunk.docId, s.score, s.chunk.page));
        }
        return results;
    }

    /** 余弦相似度（两向量维度一致；任一为零向量返回 NaN） */
    @SuppressWarnings("PMD.UseVarargs") // 双数组参数，varargs 只能修饰最后一个参数，无法转换
    private float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return Float.NaN;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return Float.NaN;
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    /** collection 名 → kbId（与 getCollectionName 互逆；非法名回退 -1 记忆库） */
    private Long kbIdOfCollection(String collection) {
        if (collection == null) {
            return -1L;
        }
        if ("conversation_memory".equals(collection)) {
            return -1L;
        }
        if (collection.startsWith(collectionPrefix)) {
            try {
                return Long.parseLong(collection.substring(collectionPrefix.length()));
            } catch (NumberFormatException ignored) {
            }
        }
        try {
            return Long.parseLong(collection);
        } catch (NumberFormatException ignored) {
        }
        return -1L;
    }

    /** 解析 collection 名（负数 kbId 用固定名字，避免非法字符） */
    private String getCollectionName(Long knowledgeBaseId) {
        if (knowledgeBaseId == null || knowledgeBaseId < 0) {
            return "conversation_memory";
        }
        return collectionPrefix + knowledgeBaseId;
    }

    /** 内存分片 */
    private static final class MemChunk {
        final long id;
        final long docId;
        final int chunkIndex;
        final String text;
        final String source;
        final int page;
        final float[] vector;

        MemChunk(long id, long docId, int chunkIndex, String text, String source, int page, float... vector) {
            this.id = id;
            this.docId = docId;
            this.chunkIndex = chunkIndex;
            this.text = text;
            this.source = source;
            this.page = page;
            this.vector = vector.clone();   // 防御性拷贝，避免外部数组被直接存储
        }
    }

    /** 分片 + 相似度得分 */
    private record ScoredChunk(MemChunk chunk, float score) {}
}
