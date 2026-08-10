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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }

    public long getTotalChunks() { return totalChunks; }
    public void setTotalChunks(long totalChunks) { this.totalChunks = totalChunks; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "KnowledgeBase{id=" + id + ", name='" + name + "', docs=" + documentCount + "}";
    }
}
