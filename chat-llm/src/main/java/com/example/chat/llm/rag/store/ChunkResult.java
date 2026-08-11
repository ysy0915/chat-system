package com.example.chat.llm.rag.store;

/**
 * 向量检索结果 — 通用返回模型。
 *
 * @param docId      文档 ID
 * @param chunkIndex 分片索引
 * @param chunkText  分片文本
 * @param score      相似度打分
 */
public record ChunkResult(
        String docId,
        int    chunkIndex,
        String chunkText,
        float  score
) {}
