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

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public void setKnowledgeBaseId(Long knowledgeBaseId) { this.knowledgeBaseId = knowledgeBaseId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "KnowledgeDocument{id=" + id + ", kb=" + knowledgeBaseId +
               ", file='" + fileName + "', chunks=" + chunkCount + ", status='" + status + "'}";
    }
}
