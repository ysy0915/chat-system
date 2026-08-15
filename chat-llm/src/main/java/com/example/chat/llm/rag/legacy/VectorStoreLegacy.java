package com.example.chat.llm.rag.legacy;

import java.util.List;

/**
 * <h2>旧版 RAG 向量存储抽象（kbId 语义）</h2>
 *
 * <p>知识库 kbId 模型的向量操作契约（Milvus 与纯内存实现均可接入）：
 * <ul>
 *   <li>{@link #ensureCollection(Long)} — 确保知识库 Collection 存在</li>
 *   <li>{@link #insertChunks(Long, Long, List, String)} — 文档分片向量化入库</li>
 *   <li>{@link #search(Long, String, int)} — 语义检索（按文本先向量化再检索）</li>
 *   <li>{@link #dropCollection(Long)} — 删除知识库向量</li>
 * </ul>
 * </p>
 *
 * <p>Milvus 实现：{@link LegacyVectorStoreService}（<code>app.rag.backend=milvus</code>，默认）；<br>
 * 纯内存实现：{@link InMemoryVectorStoreService}（<code>app.rag.backend=memory</code>，
 * standalone 模式零基础设施依赖）。</p>
 *
 * <p>{@link ChunkText} / {@link SearchResult} 为共享数据结构，
 * 业务层（对话记忆 / RAG 控制器）只依赖本接口与这两个类型，不感知具体后端。</p>
 */
public interface VectorStoreLegacy {

    /** 确保某个知识库的 Collection 存在（幂等，不存在则创建）。 */
    void ensureCollection(Long knowledgeBaseId);

    /**
     * 插入文档分片（内部向量化）。
     *
     * @param knowledgeBaseId 知识库 ID（负数映射到对话记忆库 conversation_memory）
     * @param docId           原始文档 ID
     * @param chunks          分片列表 [(text, chunkIndex), ...]
     * @param source          来源标记（文件名 / 场景）
     */
    void insertChunks(Long knowledgeBaseId, Long docId, List<ChunkText> chunks, String source);

    /**
     * 语义检索：将 query 向量化后与库内分片做余弦相似度检索。
     *
     * @return 按相似度降序的命中列表（可能为空）
     */
    List<SearchResult> search(Long knowledgeBaseId, String query, int topK);

    /** 删除某个知识库的 Collection（删知识库时调用）。 */
    void dropCollection(Long knowledgeBaseId);

    /** 存储实例名：milvus / memory */
    String name();

    /** 文档分片（入库前） */
    final class ChunkText {
        public final String text;
        public final int chunkIndex;
        /** 所在页码（PDF 物理页码；docx/txt 为 1；无页概念为 0） */
        public final int page;

        public ChunkText(String text, int chunkIndex) {
            this(text, chunkIndex, 0);
        }

        public ChunkText(String text, int chunkIndex, int page) {
            this.text = text;
            this.chunkIndex = chunkIndex;
            this.page = page;
        }
    }

    /** 检索结果 */
    final class SearchResult {
        public final String text;
        public final String source;
        public final long docId;
        public final float score;
        /** 引文页码（便于前端溯源展示「第 X 页」） */
        public final int page;

        public SearchResult(String text, String source, long docId, float score) {
            this(text, source, docId, score, 0);
        }

        public SearchResult(String text, String source, long docId, float score, int page) {
            this.text = text;
            this.source = source;
            this.docId = docId;
            this.score = score;
            this.page = page;
        }
    }
}
