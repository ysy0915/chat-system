-- ============================================================
-- 向量数据库配置表 — 统一管理多种向量存储后端
-- 
-- 设计思路：
--   llm_vector_store_config   → 通用属性表（所有向量库共有的）
--   llm_vector_store_props    → KV 扩展属性表（各厂商特有的）
-- 
-- 支持的 store_type: MILVUS / PINECONE / WEAVIATE / QDRANT / ELASTICSEARCH / CHROMA
-- ============================================================

-- ─── 1. 向量库通用属性表 ─────────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_vector_store_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_type      VARCHAR(50)  NOT NULL COMMENT '向量库类型: MILVUS, PINECONE, WEAVIATE, QDRANT, ELASTICSEARCH, CHROMA',
    name            VARCHAR(100) NOT NULL COMMENT '配置名称（唯一标识）',
    `host`          VARCHAR(255) NOT NULL DEFAULT '127.0.0.1' COMMENT '连接地址',
    port            INT          NOT NULL DEFAULT 0 COMMENT '连接端口 (gRPC/HTTP)',
    database_name   VARCHAR(255) NOT NULL DEFAULT 'default' COMMENT '数据库名 / namespace / index',
    collection_name VARCHAR(255) NOT NULL COMMENT '集合名 / collection / index',
    dimension       INT          NOT NULL DEFAULT 1536 COMMENT '向量维度',
    auth_type       VARCHAR(50)  DEFAULT 'none' COMMENT '认证方式: none / api_key / token / iam',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用 0=禁用 1=启用',
    is_default      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认向量库 0=否 1=是',
    description     VARCHAR(500) DEFAULT '' COMMENT '描述',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_name (name),
    INDEX idx_store_type (store_type),
    INDEX idx_enabled_default (enabled, is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量库通用配置表';

-- ─── 2. 向量库特殊属性 KV 表 ──────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_vector_store_props (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    store_config_id BIGINT       NOT NULL COMMENT '关联 llm_vector_store_config.id',
    prop_key        VARCHAR(100) NOT NULL COMMENT '属性键',
    prop_value      TEXT         NOT NULL COMMENT '属性值',
    prop_type       VARCHAR(20)  NOT NULL DEFAULT 'STRING' COMMENT '值类型: STRING, INT, BOOL, JSON, SECRET',
    description     VARCHAR(300) DEFAULT '' COMMENT '属性说明',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_store_config_id (store_config_id),
    INDEX idx_prop_key (store_config_id, prop_key),
    CONSTRAINT fk_store_config_id FOREIGN KEY (store_config_id) 
        REFERENCES llm_vector_store_config(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量库特殊属性KV表（厂商特有配置）';


-- ─── 3. 示例数据 ─────────────────────────────────────────────

-- 3.1 Milvus (gRPC 直连)
INSERT INTO llm_vector_store_config (store_type, name, `host`, port, database_name, collection_name, dimension, auth_type, enabled, is_default, description)
VALUES ('MILVUS', 'milvus-default', '127.0.0.1', 19530, 'default', 'rag_documents', 1536, 'none', 1, 1, '本地 Milvus 向量库');

-- Milvus 特有属性
INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description) VALUES
(1, 'grpc.keepalive.time.ms', '10000', 'INT', 'gRPC keepalive 间隔'),
(1, 'grpc.max.inbound.message.size', '104857600', 'INT', 'gRPC 最大入站消息(100MB)'),
(1, 'index.type', 'IVF_FLAT', 'STRING', '索引类型'),
(1, 'index.metric', 'L2', 'STRING', '距离度量 L2/IP/COSINE'),
(1, 'index.nlist', '1024', 'INT', 'IVF 聚类数'),
(1, 'search.nprobe', '10', 'INT', '搜索探测聚类数');

-- 3.2 Pinecone (示例, 默认禁用)
INSERT INTO llm_vector_store_config (store_type, name, `host`, port, database_name, collection_name, dimension, auth_type, enabled, is_default, description)
VALUES ('PINECONE', 'pinecone-default', 'https://my-index-xxx.svc.pinecone.io', 443, '', 'rag-documents', 1536, 'api_key', 0, 0, 'Pinecone 云端向量库');

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description) VALUES
(2, 'api_key', '${PINECONE_API_KEY}', 'SECRET', 'Pinecone API Key'),
(2, 'cloud', 'aws', 'STRING', '云平台 aws/gcp/azure'),
(2, 'region', 'us-east-1', 'STRING', 'Region'),
(2, 'metric', 'cosine', 'STRING', '距离度量'),
(2, 'pod_type', 'p1.x1', 'STRING', 'Pod 类型');

-- 3.3 Weaviate (示例, 默认禁用)
INSERT INTO llm_vector_store_config (store_type, name, `host`, port, database_name, collection_name, dimension, auth_type, enabled, is_default, description)
VALUES ('WEAVIATE', 'weaviate-default', 'https://my-cluster.weaviate.network', 443, '', 'RagDocuments', 1536, 'api_key', 0, 0, 'Weaviate 云端向量库');

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description) VALUES
(3, 'api_key', '${WEAVIATE_API_KEY}', 'SECRET', 'Weaviate API Key'),
(3, 'grpc_port', '50051', 'INT', 'gRPC 端口'),
(3, 'schema.class', 'RagDocument', 'STRING', 'Schema class 名'),
(3, 'module.text2vec', 'text2vec-transformers', 'STRING', '向量化模块');

-- 3.4 Qdrant (示例, 默认禁用)
INSERT INTO llm_vector_store_config (store_type, name, `host`, port, database_name, collection_name, dimension, auth_type, enabled, is_default, description)
VALUES ('QDRANT', 'qdrant-default', '127.0.0.1', 6334, '', 'rag_documents', 1536, 'api_key', 0, 0, '本地 Qdrant 向量库');

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description) VALUES
(4, 'api_key', '', 'SECRET', 'Qdrant API Key'),
(4, 'grpc_port', '6334', 'INT', 'gRPC 端口'),
(4, 'rest_port', '6333', 'INT', 'REST 端口'),
(4, 'prefer_grpc', 'true', 'BOOL', '优先使用 gRPC'),
(4, 'quantization', 'scalar', 'STRING', '量化方式');
