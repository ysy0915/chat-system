package com.example.chat.llm.rag.store.pinecone;

import com.example.chat.llm.rag.store.ChunkRecord;
import com.example.chat.llm.rag.store.ChunkResult;
import com.example.chat.llm.rag.store.StoreType;
import com.example.chat.llm.rag.store.VectorStoreAdapter;
import com.example.chat.llm.rag.store.VectorStoreConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Collections;

/**
 * Pinecone 向量数据库适配器骨架 — 展示如何实现一个新的 {@link VectorStoreAdapter}。
 *
 * <p>只需 4 步即可接入新向量库：
 * <ol>
 *   <li>实现 VectorStoreAdapter 接口</li>
 *   <li>在 {@link StoreType} 注册 PINECONE</li>
 *   <li>在注册中心注册本类</li>
 *   <li>在 DB 表添加配置行 + KV 属性</li>
 * </ol>
 *
 * <h3>通用属性 (来自 llm_vector_store_config)</h3>
 * <pre>
 *   host      → https://my-index-xxx.svc.pinecone.io
 *   port      → 443
 *   authType  → api_key
 * </pre>
 *
 * <h3>特殊属性 (来自 llm_vector_store_props KV)</h3>
 * <pre>
 *   api_key   → Pinecone API Key (SECRET)
 *   cloud     → aws/gcp/azure
 *   region    → us-east-1
 *   metric    → cosine/euclidean/dotproduct
 *   pod_type  → p1.x1
 * </pre>
 */
public class PineconeVectorStoreAdapter implements VectorStoreAdapter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PineconeVectorStoreAdapter.class);

    private VectorStoreConfig config;

    // 扩展点（预留）：接入 Pinecone Java SDK 后在此注入
    // private PineconeClient pineconeClient;
    // private String indexHost;

    @Override
    public StoreType getStoreType() {
        return StoreType.PINECONE;
    }

    @Override
    public void init(VectorStoreConfig config) {
        this.config = config;
        String apiKey = config.getProp("api_key", "");

        // 扩展点（预留）：初始化 PineconeClient
        // this.pineconeClient = new PineconeClient(config.getHost(), apiKey);
        log.info("[Pinecone] 初始化 host={} collection={} dim={} metric={}",
                config.getHost(), config.getCollectionName(),
                config.getDimension(), config.getProp("metric", "cosine"));
    }

    @Override
    public void insertBatch(List<ChunkRecord> records) {
        log.debug("[Pinecone] 批量插入 {} 条", records.size());
        // 扩展点（预留）：upsert (id, values, metadata) 批量写入
    }

    @Override
    public void deleteByDocId(String docId) {
        log.info("[Pinecone] 删除文档 docId={}", docId);
        // 扩展点（预留）：delete by filter { "doc_id": docId }
    }

    @Override
    public List<ChunkResult> search(List<Float> queryVector, int topK, float scoreThreshold) {
        log.debug("[Pinecone] 检索 topK={} threshold={}", topK, scoreThreshold);
        // 扩展点（预留）：query (vector, topK, includeMetadata=true, filter)
        return Collections.emptyList();
    }

    @Override
    public boolean isHealthy() {
        // 扩展点（预留）：describeIndexStats() 校验索引可用性
        return true;
    }

    @Override
    public void close() {
        log.info("[Pinecone] client 已关闭");
        // 扩展点（预留）：pineconeClient.close() 释放连接
    }
}
