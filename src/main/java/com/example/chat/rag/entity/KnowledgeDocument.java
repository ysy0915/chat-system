package com.example.chat.rag.entity;

/**
 * 知识库文档实体（对应 MySQL 表 rag_documents）
 */
public class KnowledgeDocument {

    public Long id;
    public Long knowledgeBaseId;   // 所属知识库 ID
    public String fileName;         // 原始文件名
    public String source;           // 来源标记（文件名/URL）
    public int chunkCount;          // 分片数量
    public long fileSize;           // 文件大小（字节）
    public String status;           // pending / processing / done / error
    public String errorMessage;     // 失败原因
    public String createdAt;        // 创建时间

    @Override
    public String toString() {
        return "KnowledgeDocument{id=" + id + ", kb=" + knowledgeBaseId +
               ", file='" + fileName + "', chunks=" + chunkCount + ", status='" + status + "'}";
    }
}
