package com.example.chat.llm.rag.store;

import java.util.List;

/**
 * <h2>向量数据库统一抽象接口</h2>
 *
 * 所有向量库后端（Milvus / Pinecone / Weaviate / Qdrant / Elasticsearch / Chroma）
 * 都实现该接口，上层 RagService 只依赖本接口，不感知具体实现。
 *
 * <h3>生命周期</h3>
 * <pre>
 *   init(config) → insert/delete/search → close()
 *   isHealthy() 随时可查
 * </pre>
 *
 * <h3>扩展新向量库的步骤</h3>
 * <ol>
 *   <li>实现本接口（如 {@code PineconeVectorStoreAdapter}）</li>
 *   <li>在 {@link StoreType} 注册新枚举值</li>
 *   <li>在 {@link VectorStoreRegistry} 注册映射</li>
 *   <li>在数据库 llm_vector_store_config + llm_vector_store_props 添加配置</li>
 * </ol>
 */
public interface VectorStoreAdapter {

    /**
     * 返回适配器对应的向量库类型。
     */
    StoreType getStoreType();

    /**
     * 用通用配置初始化客户端连接。
     * 厂商特有属性通过 {@link VectorStoreConfig#getExtraProps()} 读取。
     *
     * @param config 通用配置（含 KV 扩展属性）
     * @throws IllegalStateException 连接失败时抛出
     */
    void init(VectorStoreConfig config);

    /**
     * 批量插入分片记录（含向量）。
     */
    void insertBatch(List<ChunkRecord> records);

    /**
     * 按 docId 删除该文档的所有分片。
     */
    void deleteByDocId(String docId);

    /**
     * 向量相似度检索。
     *
     * @param queryVector    查询向量
     * @param topK           返回 top-K
     * @param scoreThreshold 最低相似度阈值（小于该值的忽略）
     * @return 按 score 降序排列的结果
     */
    List<ChunkResult> search(List<Float> queryVector, int topK, float scoreThreshold);

    /**
     * 健康检查。
     */
    boolean isHealthy();

    /**
     * 释放连接，关闭客户端。
     */
    void close();
}
