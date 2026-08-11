package com.example.chat.llm.rag.store.milvus;

import com.example.chat.llm.rag.store.ChunkRecord;
import com.example.chat.llm.rag.store.ChunkResult;
import com.example.chat.llm.rag.store.StoreType;
import com.example.chat.llm.rag.store.VectorStoreAdapter;
import com.example.chat.llm.rag.store.VectorStoreConfig;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量数据库适配器 — 实现 {@link VectorStoreAdapter}。
 *
 * <p>通过 {@link VectorStoreConfig} 的通用字段 + extraProps KV 扩展完成全部配置。</p>
 */
public class MilvusVectorStoreAdapter implements VectorStoreAdapter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStoreAdapter.class);

    private MilvusServiceClient client;
    private VectorStoreConfig  config;
    private String collectionName;
    private String metricType;       // L2 / IP / COSINE
    private int    nlist;            // IVF 聚类数
    private int    nprobe;           // 搜索探测数
    private String indexType;

    @Override
    public StoreType getStoreType() {
        return StoreType.MILVUS;
    }

    @Override
    public void init(VectorStoreConfig config) {
        this.config = config;
        this.collectionName = config.getCollectionName();

        // 从 extraProps KV 读取厂商特有参数
        this.indexType  = config.getProp("index.type", "IVF_FLAT");
        this.metricType = config.getProp("index.metric", "L2");
        this.nlist      = config.getPropInt("index.nlist", 1024);
        this.nprobe     = config.getPropInt("search.nprobe", 16);

        // 认证方式
        String apiKey = config.getProp("api_key", "");
        if (apiKey != null && !apiKey.isBlank()) {
            this.client = new MilvusServiceClient(
                    io.milvus.param.ConnectParam.newBuilder()
                            .withHost(config.getHost())
                            .withPort(config.getPort())
                            .withDatabaseName(config.getDatabaseName())
                            .withAuthorization(apiKey)
                            .build());
        } else {
            this.client = new MilvusServiceClient(
                    io.milvus.param.ConnectParam.newBuilder()
                            .withHost(config.getHost())
                            .withPort(config.getPort())
                            .withDatabaseName(config.getDatabaseName())
                            .build());
        }
        log.info("[Milvus] 连接 {}:{} db={} collection={} index={} metric={}",
                config.getHost(), config.getPort(), config.getDatabaseName(), collectionName, indexType, metricType);
    }

    @Override
    public void insertBatch(List<ChunkRecord> records) {
        if (records == null || records.isEmpty()) return;

        ensureCollection();
        List<String> docIds     = new ArrayList<>();
        List<Long>   chunkIdxs  = new ArrayList<>();
        List<String> texts      = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();

        for (ChunkRecord r : records) {
            docIds.add(r.docId());
            chunkIdxs.add((long) r.chunkIndex());
            texts.add(r.chunkText());
            vectors.add(r.vector());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("doc_id",      docIds));
        fields.add(new InsertParam.Field("chunk_idx",   chunkIdxs));
        fields.add(new InsertParam.Field("chunk_text",  texts));
        fields.add(new InsertParam.Field("embedding",   vectors));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build();

        R<MutationResult> resp = client.insert(insertParam);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("Milvus insert failed: " + resp.getMessage());
        }
        log.debug("[Milvus] 插入 {} 条记录", records.size());
    }

    @Override
    public void deleteByDocId(String docId) {
        if (docId == null || docId.isBlank()) return;
        String expr = "doc_id == \"" + docId + "\"";
        DeleteParam param = DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build();
        R<MutationResult> resp = client.delete(param);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("Milvus delete failed: " + resp.getMessage());
        }
        log.info("[Milvus] 删除文档 docId={}", docId);
    }

    @Override
    public List<ChunkResult> search(List<Float> queryVector, int topK, float scoreThreshold) {
        if (queryVector == null || queryVector.isEmpty()) return Collections.emptyList();

        ensureCollection();
        List<String> outFields = List.of("doc_id", "chunk_idx", "chunk_text");

        SearchParam param = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(getMetricEnum())
                .withOutFields(outFields)
                .withTopK(topK)
                .withVectors(List.of(new ArrayList<>(queryVector)))
                .withVectorFieldName("embedding")
                .withParams("{\"nprobe\":" + nprobe + "}")
                .build();

        R<SearchResults> resp = client.search(param);
        if (resp.getStatus() != 0) {
            throw new RuntimeException("Milvus search failed: " + resp.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
        List<ChunkResult> results = new ArrayList<>();

        // Milvus SDK 2.3.4 API: getIDScore(0) 返回 List<IDScore>, getFieldData(x, 0) 返回 List<?>
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);
        List<?> docIds   = wrapper.getFieldData("doc_id", 0);
        List<?> chunkIdx = wrapper.getFieldData("chunk_idx", 0);
        List<?> texts    = wrapper.getFieldData("chunk_text", 0);

        for (int i = 0; i < idScores.size(); i++) {
            float score = idScores.get(i).getScore();
            if (score < scoreThreshold) continue;

            String dId = docIds.get(i) != null ? docIds.get(i).toString() : "";
            int    cIdx = chunkIdx.get(i) != null
                    ? Integer.parseInt(chunkIdx.get(i).toString()) : 0;
            String txt = texts.get(i) != null ? texts.get(i).toString() : "";

            results.add(new ChunkResult(dId, cIdx, txt, score));
        }
        log.debug("[Milvus] 检索: topK={} threshold={} → {} 条", topK, scoreThreshold, results.size());
        return results;
    }

    @Override
    public boolean isHealthy() {
        try {
            R<Boolean> resp = client.hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
            return resp.getStatus() == 0 || resp.getStatus() == 1; // 1=collection not found (still healthy)
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ignore) { }
            log.info("[Milvus] client 已关闭");
        }
    }

    // ── 内部方法 ───────────────────────────────────────────

    private void ensureCollection() {
        R<Boolean> has = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build());
        if (has.getData() != null && has.getData()) return;

        // 创建 collection (Milvus SDK 2.3.4 API)
        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("RAG documents — auto-created by MilvusVectorStoreAdapter")
                .withShardsNum(1)
                .addFieldType(FieldType.newBuilder()
                        .withName("doc_id").withDataType(DataType.VarChar)
                        .withMaxLength(256).withPrimaryKey(true).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_idx").withDataType(DataType.Int64).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("chunk_text").withDataType(DataType.VarChar)
                        .withMaxLength(65535).build())
                .addFieldType(FieldType.newBuilder()
                        .withName("embedding").withDataType(DataType.FloatVector)
                        .withDimension(config.getDimension()).build())
                .build();

        R<RpcStatus> createResp = client.createCollection(createParam);
        if (createResp.getStatus() != 0) {
            throw new RuntimeException("Create collection failed: " + createResp.getMessage());
        }
        log.info("[Milvus] 创建 collection={}", collectionName);

        // 创建索引
        String extraParam = switch (indexType.toUpperCase()) {
            case "HNSW" -> "{\"M\":16,\"efConstruction\":200}";
            default     -> "{\"nlist\":" + nlist + "}";
        };

        CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(getIndexEnum())
                .withMetricType(getMetricEnum())
                .withExtraParam(extraParam)
                .build();

        R<RpcStatus> idxResp = client.createIndex(indexParam);
        if (idxResp.getStatus() != 0) {
            throw new RuntimeException("Create index failed: " + idxResp.getMessage());
        }
        log.info("[Milvus] 创建索引 type={} metric={} nlist={}", indexType, metricType, nlist);

        // 加载到内存
        try {
            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(collectionName).build());
            log.info("[Milvus] 加载 collection={} 到内存", collectionName);
        } catch (Exception ex) {
            log.debug("[Milvus] loadCollection: {}", ex.getMessage());
        }
    }

    private IndexType getIndexEnum() {
        return switch (indexType.toUpperCase()) {
            case "IVF_FLAT" -> IndexType.IVF_FLAT;
            case "IVF_SQ8"  -> IndexType.IVF_SQ8;
            case "IVF_PQ"   -> IndexType.IVF_PQ;
            case "HNSW"     -> IndexType.HNSW;
            case "FLAT"     -> IndexType.FLAT;
            default         -> IndexType.IVF_FLAT;
        };
    }

    private MetricType getMetricEnum() {
        return switch (metricType.toUpperCase()) {
            case "IP"     -> MetricType.IP;
            case "COSINE" -> MetricType.COSINE;
            default       -> MetricType.L2;
        };
    }
}
