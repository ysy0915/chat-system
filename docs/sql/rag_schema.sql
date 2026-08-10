-- ============================================
-- RAG 知识库相关表
-- 在本地和生产数据库均执行
-- ============================================

-- 知识库表
CREATE TABLE IF NOT EXISTS rag_knowledge_bases (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识库ID',
    name            VARCHAR(100) NOT NULL COMMENT '知识库名称',
    description     VARCHAR(500) DEFAULT '' COMMENT '描述',
    document_count  INT DEFAULT 0 COMMENT '文档数量',
    total_chunks    BIGINT DEFAULT 0 COMMENT '总分片数',
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库';

-- 知识库文档表
CREATE TABLE IF NOT EXISTS rag_documents (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '文档ID',
    knowledge_base_id BIGINT NOT NULL COMMENT '所属知识库ID',
    file_name         VARCHAR(255) NOT NULL COMMENT '原始文件名',
    source            VARCHAR(512) DEFAULT '' COMMENT '来源标记',
    chunk_count       INT DEFAULT 0 COMMENT '分片数量',
    file_size         BIGINT DEFAULT 0 COMMENT '文件大小(字节)',
    status            VARCHAR(20) DEFAULT 'pending' COMMENT 'pending/processing/done/error',
    error_message     TEXT COMMENT '失败原因',
    created_at        DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_kb_id (knowledge_base_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG知识库文档';

-- 示例：插入一个默认知识库（可选）
-- INSERT INTO rag_knowledge_bases (name, description) VALUES ('通用知识库', '系统默认知识库');
