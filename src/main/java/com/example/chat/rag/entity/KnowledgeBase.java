package com.example.chat.rag.entity;

/**
 * 知识库实体（对应 MySQL 表 rag_knowledge_bases）
 */
public class KnowledgeBase {

    public Long id;
    public String name;             // 知识库名称（如"情绪树洞FAQ"）
    public String description;      // 描述
    public int documentCount;       // 文档数量
    public long totalChunks;        // 总分片数
    public String createdAt;        // 创建时间

    @Override
    public String toString() {
        return "KnowledgeBase{id=" + id + ", name='" + name + "', docs=" + documentCount + "}";
    }
}
