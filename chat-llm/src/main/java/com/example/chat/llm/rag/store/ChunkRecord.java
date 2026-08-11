package com.example.chat.llm.rag.store;

import java.util.List;

/**
 * 文本分片记录 — 插入向量库的通用数据模型。
 *
 * @param docId      文档 ID
 * @param chunkIndex 分片索引
 * @param chunkText  分片文本
 * @param vector     分片对应的向量 (dimension 由 VectorStoreConfig 决定)
 */
public record ChunkRecord(
        String       docId,
        int          chunkIndex,
        String       chunkText,
        List<Float>  vector
) {}
