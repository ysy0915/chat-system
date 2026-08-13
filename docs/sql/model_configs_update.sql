-- ============================================================
-- model_configs 表结构变更 + 数据更新 SQL
-- 适用于: chat-system-project
-- 说明:   添加 model_type 字段并初始化所有模型配置
--
-- ⚠️ [B档] 已退役：model_configs 表不再读写（仅历史存档）。
-- 运行时统一读取 llm_provider_config / llm_model_config 等新表，
-- 数据迁移见 migrate_model_configs_to_llm.sql，本脚本仅供查档。
-- ============================================================

-- ─── 1. 表结构变更 ────────────────────────────────────────────

-- 1.1 添加 model_type 字段（已存在则跳过，MySQL 8.0.29+ 支持 IF NOT EXISTS）
ALTER TABLE model_configs
    ADD COLUMN IF NOT EXISTS model_type VARCHAR(20) NOT NULL DEFAULT 'chat'
    COMMENT '模型执行类型: chat(对话) / image(图形生成) / video(视频生成) / 3d(3D模型生成) / text_parse(文本解析) / image_parse(图片解析)';

-- 1.2 添加 updated_at 字段（用于记录更新时间，已存在则跳过）
ALTER TABLE model_configs
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 1.3 为 model_type 添加索引（便于按类型查询）
CREATE INDEX IF NOT EXISTS idx_model_configs_model_type ON model_configs (model_type);
CREATE INDEX IF NOT EXISTS idx_model_configs_enabled_type ON model_configs (enabled, model_type);


-- ─── 2. 数据更新 ────────────────────────────────────────────
-- 使用 ON DUPLICATE KEY UPDATE 实现 upsert，可重复执行

-- 2.1 id=1 deepseek-chat (对话)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    1,
    'deepseek',
    'deepseek-chat',
    'chat',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://api.deepseek.com/v1"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.2 id=2 qwen-plus (对话)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    2,
    'qwen',
    'qwen-plus',
    'chat',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.3 id=3 doubao (对话)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    3,
    'doubao',
    'doubao-seed-2-0-pro-260215',
    'chat',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://ark.cn-beijing.volces.com/api/v3"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.4 id=4 qwen-image (图形生成)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    4,
    'qwen',
    'qwen-image-2.0-pro',
    'image',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://dashscope.aliyuncs.com"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.5 id=5 dashscope wan2.7 (视频生成)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    5,
    'dashscope',
    'wan2.7-t2v',
    'video',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://dashscope.aliyuncs.com"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.6 id=6 glm-4-flash (旧的文本解析模型，已禁用)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    6,
    'zhipu',
    'glm-4-flash',
    'text_parse',
    '4f5b5256b96440659f3d1ff2afe27cf1.jfHUDmsV65fx27Pb',
    '{"baseUrl":"https://open.bigmodel.cn/api/paas/v4"}',
    100,
    0,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.7 id=7 hy-3d-3.1 (3D模型生成)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    7,
    'tencent',
    'hy-3d-3.1',
    '3d',
    'REPLACE_WITH_TENCENT_API_KEY',
    '{"baseUrl":"https://tokenhub.tencentmaas.com"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.8 id=8 qwen-vl-max (图片解析)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    8,
    'qwen',
    'qwen-vl-max',
    'image_parse',
    'REPLACE_WITH_QWEN_API_KEY',
    '{"baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1"}',
    100,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);

-- 2.8 id=9 glm-4.6v-flash (新的文本解析模型，启用)
INSERT INTO model_configs (id, provider, model, model_type, api_key_encrypted, meta, priority, enabled, created_at)
VALUES (
    9,
    'zhipu',
    'glm-4.6v-flash',
    'text_parse',
    '4f5b5256b96440659f3d1ff2afe27cf1.jfHUDmsV65fx27Pb',
    '{"baseUrl":"https://open.bigmodel.cn/api/paas/v4"}',
    90,
    1,
    NOW()
)
ON DUPLICATE KEY UPDATE
    provider       = VALUES(provider),
    model          = VALUES(model),
    model_type     = VALUES(model_type),
    meta           = VALUES(meta),
    priority       = VALUES(priority),
    enabled        = VALUES(enabled);


-- ─── 3. 验证查询 ────────────────────────────────────────────

SELECT id, provider, model, model_type, priority, enabled
FROM model_configs
ORDER BY id;
