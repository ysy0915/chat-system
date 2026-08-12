package com.example.chat.llm.rag.legacy;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 知识库文档实体（对应 MySQL 表 rag_documents）
 */
@Schema(description = "RAG 知识文档")
public class KnowledgeDocument {

    @Schema(description = "文档ID")
    public Long id;
    @Schema(description = "所属知识库ID")
    public Long knowledgeBaseId;
    @Schema(description = "原始文件名")
    public String fileName;
    @Schema(description = "来源标记")
    public String source;
    @Schema(description = "分片数量")
    public int chunkCount;
    @Schema(description = "文件大小(字节)")
    public long fileSize;
    @Schema(description = "状态: pending / processing / done / error")
    public String status;
    @Schema(description = "失败原因")
    public String errorMessage;
    @Schema(description = "创建时间")
    public String createdAt;

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
