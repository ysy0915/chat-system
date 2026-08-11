package com.example.chat.llm.rag.store;

/**
 * 向量数据库类型枚举。
 */
public enum StoreType {
    MILVUS,
    PINECONE,
    WEAVIATE,
    QDRANT,
    ELASTICSEARCH,
    CHROMA
}
