package com.example.chat.llm.rag.legacy;

import com.example.chat.llm.rag.legacy.RagChunkRepository.RagChunkRow;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>关键词检索服务（BM25 混合检索的关键词通道）</h2>
 *
 * <p>向量检索擅长语义相似但抓不住精确关键词（专有名词、编号、人名）。
 * 本服务把分片文本冗余落到 MySQL {@code rag_chunks} 表（ngram 全文索引，支持中文），
 * 提供关键词召回，由 {@link HybridSearchService} 与向量结果做 RRF 融合。</p>
 *
 * <p>全部操作幂等 / 可降级：建表失败、检索失败均不影响主链路（向量检索）。</p>
 *
 * <p>条件装配：与 {@link LegacyVectorStoreService} 等一致，仅 milvus 持久化后端
 * （{@code app.rag.enabled=true} 且 {@code app.rag.backend=milvus}）时创建。
 * standalone（backend=memory）无 MySQL 依赖，自动跳过，关键词检索由纯向量检索降级兜底。</p>
 */
@Service
@ConditionalOnExpression("'${app.rag.enabled:false}' == 'true' and '${app.rag.backend:milvus}' == 'milvus'")
public class KeywordSearchService {

    private static final Logger log = LoggerFactory.getLogger(KeywordSearchService.class);

    /** rag_chunks 建表 DDL：ngram parser 支持中文二元分词（MySQL 5.7.6+/8.0） */
    private static final String CREATE_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS rag_chunks ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                    + "kb_id BIGINT NOT NULL,"
                    + "doc_id BIGINT NOT NULL,"
                    + "chunk_index INT NOT NULL,"
                    + "source VARCHAR(512) DEFAULT '',"
                    + "page INT DEFAULT 0,"
                    + "text MEDIUMTEXT NOT NULL,"
                    + "created_at DATETIME DEFAULT CURRENT_TIMESTAMP,"
                    + "KEY idx_kb_doc (kb_id, doc_id),"
                    + "FULLTEXT KEY ft_rag_text (text) WITH PARSER ngram"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

    private final RagChunkRepository ragChunkRepository;
    private final JdbcTemplate jdbcTemplate;

    @Value("${app.rag.hybrid.keyword-enabled:false}")
    private boolean keywordEnabled;

    @Autowired
    public KeywordSearchService(RagChunkRepository ragChunkRepository,
                                @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.ragChunkRepository = ragChunkRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 启动时幂等建表；失败仅告警（关键词检索降级，不影响向量主链路） */
    @PostConstruct
    public void init() {
        if (!keywordEnabled || jdbcTemplate == null) {
            return;
        }
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            log.info("[KeywordSearch] rag_chunks 表就绪（ngram 全文索引）");
        } catch (Exception e) {
            log.warn("[KeywordSearch] 建表失败，关键词检索将降级关闭: {}", e.getMessage());
        }
    }

    /** 分片入库时同步写 rag_chunks（与向量化入库并列，均幂等） */
    public void insertChunks(Long kbId, Long docId, List<VectorStoreLegacy.ChunkText> chunks, String source) {
        if (!keywordEnabled || chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            ragChunkRepository.insertBatch(kbId, docId, source, chunks);
            log.info("[KeywordSearch] kb={} doc={} 写入 {} 条分片", kbId, docId, chunks.size());
        } catch (Exception e) {
            log.warn("[KeywordSearch] 分片写入失败 kb={} doc={}: {}", kbId, docId, e.getMessage());
        }
    }

    /**
     * 关键词检索：ngram 全文匹配，返回按相关性降序的命中（score 归一化到 0~1）。
     * 归一化采用 sigmoid 式映射 score/(1+score)，与向量余弦分大体可比。
     */
    public List<VectorStoreLegacy.SearchResult> keywordSearch(Long kbId, String query, int topK) {
        if (!keywordEnabled || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            List<RagChunkRow> rows = ragChunkRepository.keywordSearch(kbId, query.trim(), topK);
            List<VectorStoreLegacy.SearchResult> results = new ArrayList<>(rows.size());
            for (RagChunkRow row : rows) {
                double raw = row.score == null ? 0.0 : row.score;
                float normalized = (float) (raw / (raw + 1.0));
                results.add(new VectorStoreLegacy.SearchResult(
                        row.text == null ? "" : row.text,
                        row.source == null ? "" : row.source,
                        row.docId == null ? -1L : row.docId,
                        normalized,
                        row.page == null ? 0 : row.page));
            }
            log.info("[KeywordSearch] kb={} query=\"{}\" 命中 {}", kbId, query, results.size());
            return results;
        } catch (Exception e) {
            log.warn("[KeywordSearch] 检索失败 kb={}: {}", kbId, e.getMessage());
            return List.of();
        }
    }

    /** 删除文档分片（删文档时联动） */
    public void deleteByDoc(Long docId) {
        if (!keywordEnabled) {
            return;
        }
        try {
            ragChunkRepository.deleteByDoc(docId);
        } catch (Exception e) {
            log.warn("[KeywordSearch] 删除分片失败 doc={}: {}", docId, e.getMessage());
        }
    }

    /** 删除知识库分片（删知识库时联动） */
    public void deleteByKb(Long kbId) {
        if (!keywordEnabled) {
            return;
        }
        try {
            ragChunkRepository.deleteByKb(kbId);
        } catch (Exception e) {
            log.warn("[KeywordSearch] 删除分片失败 kb={}: {}", kbId, e.getMessage());
        }
    }
}
