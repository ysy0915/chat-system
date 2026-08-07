package com.example.chat.rag.service;

import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量存储服务
 * 管理 Collection 的创建、文档入库、相似度检索
 *
 * Collection 结构（kb_{knowledgeBaseId}）：
 *   - id (INT64, 主键)：文档分片自增 ID
 *   - doc_id (INT64)：原始文档 ID
 *   - chunk_index (INT64)：分片序号
 *   - text (VARCHAR, max 65535)：分片文本
 *   - source (VARCHAR, max 512)：来源（文件名/URL）
 *   - embedding (FLOAT_VECTOR, dim=1024)：向量
 *
 * 索引：HNSW + COSINE（适合语义相似度）
 */
@Service
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final MilvusServiceClient milvusClient;
    private final EmbeddingService embeddingService;

    @Value("${app.rag.milvus.collection-prefix:kb_}")
    private String collectionPrefix;

    /** HNSW 索引参数：M 值（图的连接数，越大召回越准但内存越多） */
    @Value("${app.rag.milvus.hnsw-m:16}")
    private int hnswM;

    /** HNSW 索引参数：efConstruction（构建时搜索宽度） */
    @Value("${app.rag.milvus.hnsw-ef-construction:200}")
    private int hnswEfConstruction;

    /** 检索时 ef 值（越大召回越准但越慢） */
    @Value("${app.rag.milvus.search-ef:64}")
    private int searchEf;

    public VectorStoreService(MilvusServiceClient milvusClient, EmbeddingService embeddingService) {
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
    }

    /**
     * 确保某个知识库的 Collection 存在，不存在则创建
     */
    public void ensureCollection(Long knowledgeBaseId) {
        String collectionName = collectionPrefix + knowledgeBaseId;
        int dim = embeddingService.getDimension();

        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(true)
                .build();

        FieldType docIdField = FieldType.newBuilder()
                .withName("doc_id")
                .withDataType(DataType.Int64)
                .build();

        FieldType chunkIndexField = FieldType.newBuilder()
                .withName("chunk_index")
                .withDataType(DataType.Int64)
                .build();

        FieldType textField = FieldType.newBuilder()
                .withName("text")
                .withDataType(DataType.VarChar)
                .withMaxLength(65535)
                .build();

        FieldType sourceField = FieldType.newBuilder()
                .withName("source")
                .withDataType(DataType.VarChar)
                .withMaxLength(512)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dim)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withShardsNum(1)
                .addFieldType(idField)
                .addFieldType(docIdField)
                .addFieldType(chunkIndexField)
                .addFieldType(textField)
                .addFieldType(sourceField)
                .addFieldType(embeddingField)
                .build();

        try {
            milvusClient.createCollection(createParam);
            log.info("[VectorStore] 创建 Collection: {} dim={}", collectionName, dim);
        } catch (Exception e) {
            // 已存在会抛异常，忽略
            log.debug("[VectorStore] Collection {} 可能已存在: {}", collectionName, e.getMessage());
        }

        // 创建 HNSW 索引
        try {
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.HNSW)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"M\":" + hnswM + ",\"efConstruction\":" + hnswEfConstruction + "}")
                    .build());
            log.info("[VectorStore] 创建 HNSW 索引: {}", collectionName);
        } catch (Exception e) {
            log.debug("[VectorStore] 索引可能已存在: {}", e.getMessage());
        }

        // 加载到内存（检索前必须 load）
        try {
            milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
        } catch (Exception e) {
            log.debug("[VectorStore] loadCollection: {}", e.getMessage());
        }
    }

    /**
     * 插入文档分片
     * @param knowledgeBaseId 知识库 ID
     * @param docId 文档 ID
     * @param chunks 分片列表 [(text, chunkIndex), ...]
     * @param source 来源标记
     */
    public void insertChunks(Long knowledgeBaseId, Long docId, List<ChunkText> chunks, String source) {
        if (chunks == null || chunks.isEmpty()) return;

        String collectionName = collectionPrefix + knowledgeBaseId;
        ensureCollection(knowledgeBaseId);

        // 批量生成向量
        List<String> texts = chunks.stream().map(c -> c.text).toList();
        List<float[]> vectors = embeddingService.embedBatch(texts);

        // 构建插入数据
        List<Long> docIds = new ArrayList<>();
        List<Long> chunkIndices = new ArrayList<>();
        List<String> textList = new ArrayList<>();
        List<String> sourceList = new ArrayList<>();
        List<List<Float>> embeddingList = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            docIds.add(docId);
            chunkIndices.add((long) chunks.get(i).chunkIndex);
            textList.add(chunks.get(i).text);
            sourceList.add(source);

            List<Float> vec = new ArrayList<>();
            for (float v : vectors.get(i)) vec.add(v);
            embeddingList.add(vec);
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("doc_id", docIds));
        fields.add(new InsertParam.Field("chunk_index", chunkIndices));
        fields.add(new InsertParam.Field("text", textList));
        fields.add(new InsertParam.Field("source", sourceList));
        fields.add(new InsertParam.Field("embedding", embeddingList));

        milvusClient.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build());

        log.info("[VectorStore] 插入 {} 条分片到 {}", chunks.size(), collectionName);
    }

    /**
     * 语义检索：根据 query 找最相似的 topK 个分片
     * @return 匹配的分片列表，按相似度降序
     */
    public List<SearchResult> search(Long knowledgeBaseId, String query, int topK) {
        String collectionName = collectionPrefix + knowledgeBaseId;
        float[] queryVec = embeddingService.embed(query);

        List<Float> vec = new ArrayList<>();
        for (float v : queryVec) vec.add(v);

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.COSINE)
                .withTopK(topK)
                .withVectors(List.of(vec))
                .withVectorFieldName("embedding")
                .withOutFields(List.of("text", "source", "doc_id", "chunk_index"))
                .withParams("{\"ef\":" + searchEf + "}")
                .build();

        try {
            SearchResults response = milvusClient.search(searchParam).getData();
            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getResults());

            List<SearchResult> results = new ArrayList<>();
            for (int i = 0; i < wrapper.getIDScore(0).size(); i++) {
                SearchResultsWrapper.IDScore score = wrapper.getIDScore(0).get(i);
                String text = wrapper.getFieldData("text", 0).get(i).toString();
                String source = wrapper.getFieldData("source", 0).get(i).toString();
                long docId = Long.parseLong(wrapper.getFieldData("doc_id", 0).get(i).toString());

                results.add(new SearchResult(text, source, docId, score.getScore()));
            }
            log.info("[VectorStore] 检索 kb={} query=\"{}\" topK={} 命中={}", knowledgeBaseId, query, topK, results.size());
            return results;
        } catch (Exception e) {
            log.error("[VectorStore] 检索失败 kb={} error={}", knowledgeBaseId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除某个知识库的 Collection（删知识库时调用）
     */
    public void dropCollection(Long knowledgeBaseId) {
        String collectionName = collectionPrefix + knowledgeBaseId;
        try {
            milvusClient.dropCollection(DropCollectionParam.newBuilder()
                    .withCollectionName(collectionName)
                    .build());
            log.info("[VectorStore] 删除 Collection: {}", collectionName);
        } catch (Exception e) {
            log.warn("[VectorStore] 删除 Collection 失败: {}", e.getMessage());
        }
    }

    /** 文档分片（入库前） */
    public static class ChunkText {
        public final String text;
        public final int chunkIndex;
        public ChunkText(String text, int chunkIndex) {
            this.text = text;
            this.chunkIndex = chunkIndex;
        }
    }

    /** 检索结果 */
    public static class SearchResult {
        public final String text;
        public final String source;
        public final long docId;
        public final float score;
        public SearchResult(String text, String source, long docId, float score) {
            this.text = text;
            this.source = source;
            this.docId = docId;
            this.score = score;
        }
    }
}
