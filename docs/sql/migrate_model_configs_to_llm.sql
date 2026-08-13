-- ============================================================
-- [B档] LLM 配置三源归一：model_configs → llm_* 新表 迁移脚本
-- ============================================================
-- 目标：
--   旧表 model_configs（9 行）退役，运行时统一读取新表
--   llm_provider_config / llm_provider_props / llm_model_config / llm_model_props
--
-- 设计要点：
--   1. 数据全部从 model_configs 实时 SELECT（不硬编码任何 API Key / 地址）
--   2. llm_model_config.id 保持与 model_configs.id 一致
--      → Redis 中 personal_model:{userId} 的个人绑定不失效
--   3. 每行原始 meta.baseUrl / base_url 写入 llm_model_props(base_url)
--      → BaseUrlResolver 逐模型 baseUrl 语义不变（qwen 不同行地址不同）
--   4. 幂等：按 description 标记先清理上次迁移行，再插入
--
-- 前置：
--   1. 已执行 docs/sql/llm_routing_schema.sql（新表已建）
--   2. 新表当前为空（或仅有管理面数据，本脚本只清理 description 标记为
--      'model_configs 迁移' 的行，不会动管理面数据）
--
-- 执行（在 RDS 上以应用账号执行）：
--   mysql -h<HOST> -u<USER> -p test_data < migrate_model_configs_to_llm.sql
-- ============================================================

-- ─── 0. 前置校验：旧表必须有数据 ───────────────────────────
SELECT COUNT(*) AS model_configs_rows FROM model_configs;

-- ─── 1. 清理上次迁移产生的行（保证幂等）─────────────────────
DELETE FROM llm_model_props     WHERE description = 'model_configs 迁移';
DELETE FROM llm_provider_props  WHERE description = 'model_configs 迁移';
DELETE FROM llm_model_config    WHERE description = 'model_configs 迁移';
DELETE FROM llm_provider_config WHERE description = 'model_configs 迁移';

-- ─── 2. 提供商（llm_provider_config）────────────────────────
-- base_url 取该 provider 在 model_configs 中 meta 最早一条（按 id 升序）
-- 每行精确地址由 llm_model_props.base_url 覆盖
INSERT INTO llm_provider_config
    (provider_name, base_url, auth_type, invoke_type, enabled, is_default, priority, description, created_at)
SELECT
    mp.provider,
    COALESCE(
        (SELECT COALESCE(
                    JSON_UNQUOTE(JSON_EXTRACT(mc.meta, '$.baseUrl')),
                    JSON_UNQUOTE(JSON_EXTRACT(mc.meta, '$.base_url')))
         FROM model_configs mc
         WHERE mc.provider = mp.provider AND mc.meta IS NOT NULL AND mc.meta <> ''
         ORDER BY mc.id ASC LIMIT 1),
        ''),
    'api_key', 'rest', 1, 0, 100, 'model_configs 迁移', NOW()
FROM (SELECT DISTINCT provider FROM model_configs) mp
ON DUPLICATE KEY UPDATE
    base_url    = VALUES(base_url),
    invoke_type = VALUES(invoke_type),
    description = VALUES(description);

-- ─── 3. 提供商 API Key（llm_provider_props，SECRET）─────────
INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description)
SELECT DISTINCT p.id, 'api_key', m.api_key_encrypted, 'SECRET', 'model_configs 迁移'
FROM model_configs m
JOIN llm_provider_config p ON p.provider_name = m.provider
WHERE m.api_key_encrypted IS NOT NULL AND m.api_key_encrypted <> '';

-- ─── 4. 模型（llm_model_config，显式 id 与旧表一致）──────────
INSERT INTO llm_model_config
    (id, provider_config_id, model_name, display_name, model_type, max_tokens,
     enabled, is_default, priority, description, created_at)
SELECT
    m.id, p.id, m.model, m.model, m.model_type, 4096,
    m.enabled, 0, m.priority, 'model_configs 迁移', m.created_at
FROM model_configs m
JOIN llm_provider_config p ON p.provider_name = m.provider
ON DUPLICATE KEY UPDATE
    provider_config_id = VALUES(provider_config_id),
    model_name         = VALUES(model_name),
    model_type         = VALUES(model_type),
    max_tokens         = VALUES(max_tokens),
    enabled            = VALUES(enabled),
    priority           = VALUES(priority),
    description        = VALUES(description);

-- ─── 5. 模型级 base_url 覆盖（llm_model_props）───────────────
-- 保留旧表每行 meta 中的精确 baseUrl，逐模型覆盖提供商默认地址
INSERT INTO llm_model_props (model_config_id, prop_key, prop_value, prop_type, description)
SELECT
    m.id, 'base_url',
    COALESCE(
        JSON_UNQUOTE(JSON_EXTRACT(m.meta, '$.baseUrl')),
        JSON_UNQUOTE(JSON_EXTRACT(m.meta, '$.base_url')),
        ''),
    'STRING', 'model_configs 迁移'
FROM model_configs m
WHERE m.meta IS NOT NULL AND m.meta <> '';

-- ─── 6. 验证 ────────────────────────────────────────────────
SELECT CONCAT('== 提供商 (期望 6) ==') AS _;
SELECT id, provider_name, base_url, invoke_type, enabled FROM llm_provider_config WHERE description = 'model_configs 迁移' ORDER BY id;

SELECT CONCAT('== 模型 (期望 9, id 与旧表一致) ==') AS _;
SELECT id, provider_config_id, model_name, model_type, max_tokens, enabled, priority
FROM llm_model_config WHERE description = 'model_configs 迁移' ORDER BY id;

SELECT CONCAT('== API Key (脱敏) ==') AS _;
SELECT p.provider_name, LEFT(pp.prop_value, 20) AS key_preview, pp.prop_type
FROM llm_provider_props pp
JOIN llm_provider_config p ON pp.provider_config_id = p.id
WHERE pp.prop_key = 'api_key' AND pp.description = 'model_configs 迁移';

SELECT CONCAT('== 模型级 base_url ==') AS _;
SELECT model_config_id, prop_value FROM llm_model_props WHERE prop_key = 'base_url' ORDER BY model_config_id;

SELECT CONCAT('== 与旧表对照 ==') AS _;
SELECT mc.id, mc.provider, mc.model, mc.model_type, mc.priority, mc.enabled
FROM model_configs mc ORDER BY mc.id;
