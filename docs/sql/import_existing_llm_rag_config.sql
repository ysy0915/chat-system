-- ============================================================
-- 导入生产现有 RAG + 大模型配置到新表
-- ============================================================
-- 数据来源:
--   model_configs 表 → 6 个提供商 (含真实 API Key) + 9 个模型
--   chat-common prod  → 生产 Milvus 向量库 / Embedding 模型
--   RagProperties.java → chat-llm RAG 默认参数
--
-- 前置: llm_vector_store_schema.sql + llm_routing_schema.sql 已执行
-- 幂等: 基于唯一键 ON DUPLICATE KEY UPDATE, 可重复执行
-- 关键: model_configs.api_key_encrypted → llm_provider_props (SECRET)
-- ============================================================

-- ─── 1. 大模型提供商 (6 个 — 来源: model_configs) ──────────
-- 数据映射:
--   model_configs.provider      → llm_provider_config.provider_name
--   model_configs.meta.baseUrl  → base_url
--   override: 默认 invoke_type='rest', 按平台特征标记 sdk
INSERT INTO llm_provider_config
    (provider_name, base_url, auth_type, invoke_type, enabled, is_default, priority, description)
VALUES
    ('deepseek', 'https://api.deepseek.com/v1',                             'api_key', 'rest', 1, 1, 1, 'DeepSeek — 现有生产配置'),
    ('qwen',     'https://dashscope.aliyuncs.com/compatible-mode/v1',       'api_key', 'rest', 1, 0, 2, '阿里通义千问 + DashScope — 现有生产配置'),
    ('doubao',   'https://ark.cn-beijing.volces.com/api/v3',               'api_key', 'rest', 1, 0, 3, '字节豆包 — 现有生产配置'),
    ('zhipu',    'https://open.bigmodel.cn/api/paas/v4',                   'api_key', 'rest', 1, 0, 4, '智谱 GLM — 现有生产配置'),
    ('tencent',  'https://tokenhub.tencentmaas.com',                       'api_key', 'rest', 1, 0, 5, '腾讯混元 3D — 现有生产配置'),
    ('openai',   'https://api.openai.com',                                 'api_key', 'sdk',  1, 0, 6, 'OpenAI — SDK 预留 (chat-llm 默认 Embedding)')
ON DUPLICATE KEY UPDATE
    base_url     = VALUES(base_url),
    invoke_type  = VALUES(invoke_type),
    enabled      = VALUES(enabled),
    description  = VALUES(description);

-- ─── 2. API Key 属性 — 导入 model_configs 真实密钥 ──────────
-- 来源: model_configs.api_key_encrypted (生产实际在用)
-- prop_type=SECRET 标记, 日志脱敏
INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', 'sk-b355826a7bc4436fb725621b6b7ed69d', 'SECRET',     'model_configs id=1 — deepseek-chat key'
FROM llm_provider_config WHERE provider_name = 'deepseek'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'default_temperature', '0.7', 'STRING', '默认对话温度'
FROM llm_provider_config WHERE provider_name = 'deepseek'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', 'sk-ws-H.EIMXHPP.2YAf.MEYCIQDjUR775CRWxkkPL0j8CVzPgRqMoTqn1o0tF3ckc4M1tgIhAOT2PTD-7liJ-ts5JBlk9NhQmuwY-rJJA91dLCOuSbRb', 'SECRET', 'model_configs id=2/4/5/8 — dashscope key (通义/百炼共用)'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', 'ark-8a35406d-11dd-486d-aae9-7cb4cf8d3997-f09ad', 'SECRET', 'model_configs id=3 — 火山 Ark key'
FROM llm_provider_config WHERE provider_name = 'doubao'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', '4f5b5256b96440659f3d1ff2afe27cf1.jfHUDmsV65fx27Pb', 'SECRET', 'model_configs id=6/9 — 智谱 key'
FROM llm_provider_config WHERE provider_name = 'zhipu'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', 'sk-OFpAPWo0PnP0DlwP5bF9BTyaciWT55822dQRFjyE5jFWvCoz', 'SECRET', 'model_configs id=7 — 腾讯混元 key'
FROM llm_provider_config WHERE provider_name = 'tencent'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'api_key', '${OPENAI_API_KEY:}', 'SECRET', 'OpenAI Key — 环境变量 (chat-llm RagProperties 使用)'
FROM llm_provider_config WHERE provider_name = 'openai'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

-- ─── 3. 模型配置 — 来源: model_configs 全部 9 条 ────────────
-- 模型类型映射: chat→chat / image→vision / video→vision / 3d→3d / text_parse→chat / image_parse→vision

-- 3.1 DeepSeek
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'deepseek-chat', 'DeepSeek Chat', 'chat', 65536, 1, 1, 100, 'model_configs id=1 — 生产默认生成模型'
FROM llm_provider_config WHERE provider_name = 'deepseek'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- 3.2 通义千问 + DashScope (合并供应商 — qwen 旗下涵盖 chat/image/video 模型)
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'qwen-plus', '通义千问 Plus', 'chat', 8192, 1, 1, 100, 'model_configs id=2 — 千问对话'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'qwen-image-2.0-pro', '通义万象 2.0 Pro', 'vision', 4096, 1, 0, 100, 'model_configs id=4 — 图片生成'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'wan2.7-t2v', '通义万相 文生视频', 'vision', 4096, 1, 0, 100, 'model_configs id=5 — 原 dashscope 供应商, 合并至 qwen'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'qwen-vl-max', '通义千问 VL Max', 'vision', 4096, 1, 0, 100, 'model_configs id=8 — 多模态理解'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- Embedding 模型 (chat-common 生产 EMBEDDING_MODEL=text-embedding-v4, dim=1024)
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'text-embedding-v4', '通义 Embedding V4', 'embedding', 1024, 1, 1, 0, '生产 Embedding (1024维) — 来源 chat-common application.yml'
FROM llm_provider_config WHERE provider_name = 'qwen'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- 3.3 豆包
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'doubao-seed-character-260628', '豆包 Seed Character', 'chat', 32768, 1, 1, 100, 'model_configs id=3 — 豆包角色扮演'
FROM llm_provider_config WHERE provider_name = 'doubao'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- 3.4 智谱
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'glm-4-flash', 'GLM-4 Flash', 'chat', 4096, 0, 0, 100, 'model_configs id=6 — 文本解析 (已禁用)'
FROM llm_provider_config WHERE provider_name = 'zhipu'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 0, priority = VALUES(priority);

INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'glm-4.6v-flash', 'GLM-4.6V Flash', 'chat', 4096, 1, 1, 90, 'model_configs id=9 — 多模态文本解析'
FROM llm_provider_config WHERE provider_name = 'zhipu'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- 3.5 腾讯混元
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'hy-3d-3.1', '混元 3D 3.1', '3d', 4096, 1, 1, 100, 'model_configs id=7 — 3D 生成'
FROM llm_provider_config WHERE provider_name = 'tencent'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- 3.6 OpenAI (chat-llm RagProperties 默认 Embedding)
INSERT INTO llm_model_config
    (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description)
SELECT id, 'text-embedding-3-small', 'OpenAI Embedding 3 Small', 'embedding', 1536, 1, 1, 0, 'chat-llm RagProperties 默认 (1536维, 需 openai key)'
FROM llm_provider_config WHERE provider_name = 'openai'
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), max_tokens = VALUES(max_tokens), enabled = 1, priority = VALUES(priority);

-- ─── 4. 向量库配置 — Milvus (生产) ─────────────────────────
--   生产 Milvus: 172.23.172.13:19530 (内网) / 开发: 127.0.0.1
--   llm_vector_store_config 的 dimension 需与生产 embedding dim=1024 对齐
--   本表供 chat-llm 使用, 但 collection/维度可配置覆盖
INSERT INTO llm_vector_store_config
    (store_type, name, `host`, port, database_name, collection_name, dimension, auth_type, enabled, is_default, description)
VALUES
    ('MILVUS', 'milvus-default', '127.0.0.1', 19530, 'default', 'rag_documents', 1024, 'none', 1, 1,
     '现有 Milvus 向量库 (生产: 172.23.172.13:19530 内网, 部署时覆盖) — dim 1024 匹配生产 text-embedding-v4')
ON DUPLICATE KEY UPDATE
    dimension   = VALUES(dimension),
    enabled     = VALUES(enabled);

-- 索引参数 (MilvusVectorStoreAdapter 默认 IVF_FLAT)
INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'index.type',    'IVF_FLAT', 'STRING', '索引类型'
FROM llm_vector_store_config WHERE name = 'milvus-default'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'index.metric',  'L2',       'STRING', '距离度量 L2/IP/COSINE'
FROM llm_vector_store_config WHERE name = 'milvus-default'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'index.nlist',   '1024',     'INT',    'IVF 聚类数'
FROM llm_vector_store_config WHERE name = 'milvus-default'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'search.nprobe', '16',       'INT',    '搜索探测数 (Java 默认)'
FROM llm_vector_store_config WHERE name = 'milvus-default'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

INSERT INTO llm_vector_store_props (store_config_id, prop_key, prop_value, prop_type, description)
SELECT id, 'grpc.keepalive.time.ms', '10000', 'INT', 'gRPC keepalive (ms)'
FROM llm_vector_store_config WHERE name = 'milvus-default'
ON DUPLICATE KEY UPDATE prop_value = VALUES(prop_value);

-- ─── 5. RAG 数据源 — 默认知识库 ────────────────────────────
--   生产: Milvus + qwen/text-embedding-v4 + deepseek/deepseek-chat
--   检索配置: 来源 chat-common prod (SCORE_THRESHOLD_HIGH=0.3) + RagProperties (top_k=5, chunk 500/50)
INSERT INTO llm_data_source
    (name, display_name, source_type, store_config_id, store_collection,
     embedding_provider, embedding_model, gen_provider_config_id, gen_model_config_id,
     top_k, score_threshold, chunk_size, chunk_overlap, enabled, is_default, priority, description)
SELECT
    'default-rag', '默认知识库', 'RAG',
    (SELECT id FROM llm_vector_store_config WHERE name = 'milvus-default'),
    'rag_documents',
    'qwen', 'text-embedding-v4',
    (SELECT id FROM llm_provider_config WHERE provider_name = 'deepseek'),
    (SELECT m.id FROM llm_model_config m
        JOIN llm_provider_config p ON m.provider_config_id = p.id
        WHERE p.provider_name = 'deepseek' AND m.model_name = 'deepseek-chat'),
    5, 0.3, 500, 50, 1, 1, 0,
    '默认 RAG 数据源 — Milvus + qwen Embedding V4 + DeepSeek 生成 (生产配置)'
ON DUPLICATE KEY UPDATE
    display_name        = VALUES(display_name),
    store_config_id     = VALUES(store_config_id),
    embedding_provider  = VALUES(embedding_provider),
    embedding_model     = VALUES(embedding_model),
    gen_provider_config_id = VALUES(gen_provider_config_id),
    gen_model_config_id = VALUES(gen_model_config_id),
    top_k               = VALUES(top_k),
    score_threshold     = VALUES(score_threshold),
    chunk_size          = VALUES(chunk_size),
    chunk_overlap       = VALUES(chunk_overlap),
    enabled             = VALUES(enabled);

-- ─── 6. 验证 ────────────────────────────────────────────────
SELECT CONCAT('== ', @label := '提供商 (6个)', ' ==') AS _;
SELECT id, provider_name, base_url, invoke_type, enabled, is_default FROM llm_provider_config ORDER BY priority;

SELECT CONCAT('== ', @label := '模型 (12个)', ' ==') AS _;
SELECT m.id, p.provider_name, m.model_name, m.model_type, m.max_tokens, m.enabled, m.is_default
FROM llm_model_config m JOIN llm_provider_config p ON m.provider_config_id = p.id
ORDER BY p.priority, FIELD(m.model_type, 'chat', 'embedding', 'vision', '3d'), m.priority;

SELECT CONCAT('== ', @label := 'API Keys (已导入)', ' ==') AS _;
SELECT pp.id, p.provider_name, pp.prop_key, pp.prop_type, LEFT(pp.prop_value, 25) AS prop_value_preview
FROM llm_provider_props pp JOIN llm_provider_config p ON pp.provider_config_id = p.id
WHERE pp.prop_key = 'api_key';

SELECT CONCAT('== ', @label := '向量库', ' ==') AS _;
SELECT id, store_type, name, dimension, collection_name, `host`, port FROM llm_vector_store_config;

SELECT CONCAT('== ', @label := 'RAG 数据源', ' ==') AS _;
SELECT id, name, embedding_provider, embedding_model, top_k, score_threshold, chunk_size FROM llm_data_source;
