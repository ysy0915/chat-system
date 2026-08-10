package com.example.chat.rag.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识库实体（对应 MySQL 表 rag_knowledge_bases）
 */
@Schema(description = "RAG 知识库")
public class KnowledgeBase {

    @Schema(description = "知识库ID")
    public Long id;
    @Schema(description = "知识库名称", example = "情绪树洞FAQ")
    public String name;
    @Schema(description = "描述")
    public String description;
    @Schema(description = "文档数量")
    public int documentCount;
    @Schema(description = "总分片数")
    public long totalChunks;
    @Schema(description = "创建时间")
    public String createdAt;

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
