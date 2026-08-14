package com.example.chat.storage;

import java.util.List;

/**
 * <h2>向量存储 SPI 接口（Milvus / FAISS / ES 向量检索… 热插拔）</h2>
 *
 * <p>[B档] 存储平台化抽象。接口方法为<b>通用向量语义</b>（collection 名 + 向量 + topK），
 * 不绑定任何具体向量库 SDK，具体实现内部完成 SDK 适配与索引/加载细节。</p>
 *
 * <p>现有 {@code chat-llm} 的 {@code LegacyVectorStoreService}（Milvus）实现本接口，
 * 通过 {@link StorageRegistry} 注册为 type={@code vector}。</p>
 *
 * @see Storage
 */
public interface VectorStore extends Storage {

    /** 存储大类类型标识 */
    @Override
    default String type() {
        return "vector";
    }

    /**
     * 确保指定 collection 存在（不存在则创建并建索引/加载）。
     *
     * @param collection collection 名（如 kb_1 / conversation_memory）
     * @param dimension  向量维度；实现可忽略而使用自身配置（以配置为准）
     */
    void ensureCollection(String collection, int dimension);

    /**
     * 批量插入向量记录。
     *
     * @param collection collection 名
     * @param records    向量记录列表（文本 + 来源 + 向量）
     */
    void insert(String collection, List<VectorRecord> records);

    /**
     * 向量相似度检索。
     *
     * @param collection  collection 名
     * @param queryVector 查询向量（维度须与 collection 一致）
     * @param topK        返回条数
     * @return 按相似度降序的命中列表（可能为空）
     */
    List<VectorHit> search(String collection, float[] queryVector, int topK);

    /**
     * 删除整个 collection（含数据）。
     */
    void dropCollection(String collection);

    /** 向量记录（入库单元） */
    final class VectorRecord {
        public final String text;
        public final String source;
        public final float[] vector;

        public VectorRecord(String text, String source, float... vector) {
            this.text = text;
            this.source = source;
            this.vector = vector.clone();   // 防御性拷贝，避免外部数组被直接存储
        }
    }

    /** 向量检索命中 */
    final class VectorHit {
        public final String text;
        public final String source;
        public final float score;

        public VectorHit(String text, String source, float score) {
            this.text = text;
            this.source = source;
            this.score = score;
        }
    }
}
