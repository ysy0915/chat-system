-- ============================================================
-- LLM 多模型 + RAG 多数据源 路由管理层表设计  (v2 — 增加 invoke_type)
--
-- [B档] 状态说明（2026-08-13）：
--   ✅ 在用：llm_provider_config / llm_provider_props / llm_model_config / llm_model_props
--      —— LLM 配置三源归一后为唯一运行时数据源
--      （ModelConfigRepository 已从 model_configs 切换至这些表，
--       数据迁移见 migrate_model_configs_to_llm.sql）
--   🗑 已删除（2026-08-13）：llm_data_source / llm_data_source_props / llm_vector_store_config /
--      llm_vector_store_props / model_configs —— 新版 RAG 体系已下线（RagService/RAGController/
--      RagGrpcService 等代码已删除）且三源归一后旧表退役，均无任何代码读写，
--      生产库已 DROP（备份 /opt/app/backup/deprecated_tables_20260813.sql）。
--
-- 设计思想：
--   llm_provider_config   → 大模型提供商通用属性表
--   llm_provider_props    → 提供商 KV 扩展属性表（api_key / path 等）
--   llm_model_config      → 模型通用属性表
--   llm_model_props       → 模型 KV 扩展属性表（base_url 覆盖等）
--   llm_data_source       → 🗑 已删除 RAG 数据源配置表（2026-08-13）
--   llm_data_source_props → 🗑 已删除 RAG 数据源 KV 扩展表（2026-08-13）
--
-- ⚠️ 警告：第 7 节示例 INSERT（provider/model/数据源）仅用于演示，
--   生产环境请勿执行 —— 会与 migrate_model_configs_to_llm.sql 的迁移数据冲突。
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

-- ─── 5~6. RAG 数据源表 ─────────────────────────────────────
-- 🗑 已删除（2026-08-13）：llm_data_source / llm_data_source_props / llm_vector_store_config /
--    llm_vector_store_props —— 新版 RAG 体系下线后无任何代码读写，生产库已 DROP，
--    备份见 /opt/app/backup/deprecated_tables_20260813.sql。

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

-- 7.3 RAG 数据源示例已随废弃表删除（llm_data_source 已 DROP，2026-08-13）


-- ─── 8. ALTER 脚本 — 已存在库的增量升级 ────────────────────

-- ALTER TABLE llm_provider_config
--     ADD COLUMN invoke_type VARCHAR(20) NOT NULL DEFAULT 'rest'
--     COMMENT '调用方式: rest (HTTP REST API) / sdk (OpenAI SDK)'
--     AFTER auth_type;
