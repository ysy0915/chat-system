-- ============================================================
-- LLM 多模型 + RAG 多数据源 路由管理层表设计  (v2 — 增加 invoke_type)
--
-- 设计思想：
--   llm_provider_config   → 大模型提供商通用属性表
--   llm_provider_props    → 提供商 KV 扩展属性表
--   llm_model_config      → 模型通用属性表
--   llm_model_props       → 模型 KV 扩展属性表
--   llm_data_source       → RAG 数据源配置表 (捆绑向量库+Embedding+LLM)
--   llm_data_source_props → 数据源 KV 扩展属性表
-- ============================================================

-- ─── 1. 大模型提供商通用属性表 ─────────────────────────────

CREATE TABLE IF NOT EXISTS llm_provider_config (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    provider_name   VARCHAR(100) NOT NULL COMMENT '提供商名称 deepseek/qwen/doubao/openai',
    base_url        VARCHAR(500) NOT NULL COMMENT 'API 基础地址',
    auth_type       VARCHAR(50)  NOT NULL DEFAULT 'api_key' COMMENT '认证方式: api_key / oauth2 / iam',
    invoke_type     VARCHAR(20)  NOT NULL DEFAULT 'rest' COMMENT '调用方式: rest (HTTP REST API) / sdk (OpenAI SDK)',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    is_default      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否默认提供商',
    priority        INT          NOT NULL DEFAULT 0 COMMENT '优先级(越小越优先)',
    description     VARCHAR(500) DEFAULT '' COMMENT '描述',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_name (provider_name),
    INDEX idx_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型提供商配置表';

-- ─── 2. 大模型提供商 KV 表 ─────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_provider_props (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_config_id BIGINT       NOT NULL COMMENT '关联 llm_provider_config.id',
    prop_key           VARCHAR(100) NOT NULL,
    prop_value         TEXT         NOT NULL,
    prop_type          VARCHAR(20)  NOT NULL DEFAULT 'STRING' COMMENT 'STRING/INT/BOOL/SECRET',
    description        VARCHAR(300) DEFAULT '',
    INDEX idx_provider_prop (provider_config_id, prop_key),
    CONSTRAINT fk_provider_props FOREIGN KEY (provider_config_id)
        REFERENCES llm_provider_config(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型提供商 KV 扩展表';

-- ─── 3. 模型通用属性表 ────────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_model_config (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_config_id BIGINT       NOT NULL COMMENT '关联 llm_provider_config.id',
    model_name         VARCHAR(100) NOT NULL COMMENT '模型名 deepseek-chat / gpt-4o',
    display_name       VARCHAR(200) DEFAULT '' COMMENT '展示名称',
    model_type         VARCHAR(50)  NOT NULL DEFAULT 'chat' COMMENT 'chat / embedding / rerank / vision',
    max_tokens         INT          DEFAULT 4096 COMMENT '最大 token 数',
    enabled            TINYINT(1)   NOT NULL DEFAULT 1,
    is_default         TINYINT(1)   NOT NULL DEFAULT 0,
    priority           INT          NOT NULL DEFAULT 0 COMMENT '模型级优先级',
    description        VARCHAR(500) DEFAULT '',
    created_at         DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_provider_model (provider_config_id, model_name),
    INDEX idx_provider_enabled (provider_config_id, enabled),
    CONSTRAINT fk_model_provider FOREIGN KEY (provider_config_id)
        REFERENCES llm_provider_config(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型配置表';

-- ─── 4. 模型 KV 表 ────────────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_model_props (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    model_config_id BIGINT       NOT NULL COMMENT '关联 llm_model_config.id',
    prop_key        VARCHAR(100) NOT NULL,
    prop_value      TEXT         NOT NULL,
    prop_type       VARCHAR(20)  NOT NULL DEFAULT 'STRING',
    description     VARCHAR(300) DEFAULT '',
    INDEX idx_model_prop (model_config_id, prop_key),
    CONSTRAINT fk_model_props FOREIGN KEY (model_config_id)
        REFERENCES llm_model_config(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='大模型 KV 扩展表';

-- ─── 5. RAG 数据源配置表 ──────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_data_source (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    name              VARCHAR(100) NOT NULL COMMENT '数据源唯一标识',
    display_name      VARCHAR(200) DEFAULT '' COMMENT '展示名称',
    source_type       VARCHAR(50)  NOT NULL DEFAULT 'RAG' COMMENT 'RAG / AGENT_RAG / MULTI_MODAL_RAG',

    -- 向量库绑定
    store_config_id   BIGINT       COMMENT '关联 llm_vector_store_config.id(NULL=不绑定)',
    store_collection  VARCHAR(255) DEFAULT '' COMMENT '覆盖向量库的 collection',

    -- Embedding 绑定
    embedding_provider VARCHAR(100) DEFAULT '' COMMENT 'Embedding 提供商',
    embedding_model   VARCHAR(100) DEFAULT '' COMMENT 'Embedding 模型',

    -- 生成模型绑定
    gen_provider_config_id BIGINT  COMMENT '关联 llm_provider_config.id',
    gen_model_config_id    BIGINT  COMMENT '关联 llm_model_config.id',

    -- 检索参数
    top_k              INT        NOT NULL DEFAULT 5 COMMENT '默认检索条数',
    score_threshold    FLOAT      NOT NULL DEFAULT 0.5 COMMENT '相似度阈值',
    chunk_size         INT        NOT NULL DEFAULT 500 COMMENT '分块大小',
    chunk_overlap      INT        NOT NULL DEFAULT 50 COMMENT '分块重叠',

    -- 管理
    enabled            TINYINT(1) NOT NULL DEFAULT 1,
    is_default         TINYINT(1) NOT NULL DEFAULT 0,
    priority           INT        NOT NULL DEFAULT 0 COMMENT '优先级',
    description        VARCHAR(500) DEFAULT '',
    created_at         DATETIME   DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME   DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_name (name),
    INDEX idx_enabled_priority (enabled, priority),
    INDEX idx_store_config (store_config_id),
    INDEX idx_gen_provider (gen_provider_config_id),
    CONSTRAINT fk_ds_store FOREIGN KEY (store_config_id)
        REFERENCES llm_vector_store_config(id) ON DELETE SET NULL,
    CONSTRAINT fk_ds_gen_provider FOREIGN KEY (gen_provider_config_id)
        REFERENCES llm_provider_config(id) ON DELETE SET NULL,
    CONSTRAINT fk_ds_gen_model FOREIGN KEY (gen_model_config_id)
        REFERENCES llm_model_config(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 数据源配置表';

-- ─── 6. RAG 数据源 KV 表 ──────────────────────────────────

CREATE TABLE IF NOT EXISTS llm_data_source_props (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    data_source_id     BIGINT       NOT NULL COMMENT '关联 llm_data_source.id',
    prop_key           VARCHAR(100) NOT NULL,
    prop_value         TEXT         NOT NULL,
    prop_type          VARCHAR(20)  NOT NULL DEFAULT 'STRING',
    description        VARCHAR(300) DEFAULT '',
    INDEX idx_ds_prop (data_source_id, prop_key),
    CONSTRAINT fk_ds_props FOREIGN KEY (data_source_id)
        REFERENCES llm_data_source(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 数据源 KV 扩展表';


-- ─── 7. 示例数据 ───────────────────────────────────────────

-- 7.1 大模型提供商
INSERT INTO llm_provider_config (provider_name, base_url, auth_type, invoke_type, enabled, is_default, priority, description) VALUES
('deepseek', 'https://api.deepseek.com',                               'api_key', 'rest', 1, 1, 1, 'DeepSeek 大模型 (REST)'),
('qwen',     'https://dashscope.aliyuncs.com/compatible-mode/v1',     'api_key', 'rest', 1, 0, 2, '阿里通义千问 (REST)'),
('doubao',   'https://ark.cn-beijing.volces.com/api/v3',              'api_key', 'rest', 1, 0, 3, '字节豆包 (REST)'),
('openai',   'https://api.openai.com',                                 'api_key', 'sdk',  1, 0, 4, 'OpenAI (SDK)');

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description) VALUES
(1, 'api_key', '${LLM_DEEPSEEK_KEY}', 'SECRET', 'DeepSeek API Key'),
(1, 'default_temperature', '0.7', 'STRING', '默认温度'),
(2, 'api_key', '${LLM_QWEN_KEY}', 'SECRET', '千问 API Key'),
(3, 'api_key', '${LLM_DOUBAO_KEY}', 'SECRET', '豆包 API Key'),
(4, 'api_key', '${OPENAI_API_KEY}',  'SECRET', 'OpenAI API Key');

-- 7.2 模型配置
INSERT INTO llm_model_config (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description) VALUES
-- DeepSeek
(1, 'deepseek-chat',       'DeepSeek V3 Chat',    'chat',      65536, 1, 1, 1, 'DeepSeek 通用对话'),
(1, 'deepseek-reasoner',  'DeepSeek R1 Reasoner', 'chat',      65536, 1, 0, 2, 'DeepSeek 推理增强'),
-- 千问
(2, 'qwen-max',           '通义千问 Max',         'chat',      8192,  1, 1, 1, '千问旗舰'),
(2, 'qwen-plus',          '通义千问 Plus',        'chat',      8192,  1, 0, 2, '千问增强'),
(2, 'text-embedding-v3',  '千问 Embedding V3',    'embedding', 8192,  1, 1, 0, '千问向量化'),
-- 豆包
(3, 'doubao-pro-32k',     '豆包 Pro 32K',         'chat',      32768, 1, 1, 1, '豆包专业版'),
-- OpenAI
(4, 'gpt-4o',             'GPT-4o',               'chat',      16384, 1, 1, 1, 'OpenAI GPT-4o'),
(4, 'gpt-4o-mini',        'GPT-4o Mini',          'chat',      16384, 1, 0, 2, 'OpenAI GPT-4o Mini'),
(4, 'gpt-4-turbo',        'GPT-4 Turbo',          'chat',      4096,  1, 0, 3, 'OpenAI GPT-4 Turbo');

-- 7.3 RAG 数据源
INSERT INTO llm_data_source (name, display_name, source_type, store_config_id,
    embedding_provider, embedding_model, gen_provider_config_id, gen_model_config_id,
    top_k, score_threshold, chunk_size, chunk_overlap, enabled, is_default, priority, description) VALUES
('default-rag', '默认知识库', 'RAG', 1,
 'qwen', 'text-embedding-v3', 1, 1,
 5, 0.5, 500, 50, 1, 1, 0, '默认 RAG 数据源 — Milvus + 千问 Embedding + DeepSeek 生成'),
('pinecone-kb', 'Pinecone 知识库', 'RAG', 2,
 'openai', 'text-embedding-3-small', 2, 2,
 10, 0.6, 800, 100, 0, 0, 1, 'Pinecone 数据源 (示例，需启用 Pinecone)');


-- ─── 8. ALTER 脚本 — 已存在库的增量升级 ────────────────────

-- ALTER TABLE llm_provider_config
--     ADD COLUMN invoke_type VARCHAR(20) NOT NULL DEFAULT 'rest'
--     COMMENT '调用方式: rest (HTTP REST API) / sdk (OpenAI SDK)'
--     AFTER auth_type;
