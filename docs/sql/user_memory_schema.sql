-- ============================================================
-- 长期记忆 (L2) + 用户画像 (L3) + 技能自进化 (Step3) 表结构
-- 数据库：test_data（RDS）
-- 执行方式：mysql -h<rds> -uyangsy -p test_data < user_memory_schema.sql
-- 幂等：user_profiles / skill_registry 均含唯一键，可重复执行
-- ============================================================

-- ───────────────────────────────────────────────
-- L3: 用户画像（结构化偏好持久化）
-- 说明：Redis 画像为主缓存（user_profile:{scene}:{userId}），本表持久化兜底；
--       每轮对话 LLM 提炼的情景/情绪/偏好合并后 upsert 到本表。
-- ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_profiles (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT       NOT NULL COMMENT '用户ID',
  scene             VARCHAR(32)  NOT NULL DEFAULT 'personal' COMMENT '场景 personal/treehole/chat',
  scene_desc        VARCHAR(500) DEFAULT '' COMMENT '用户当前情景（一句话）',
  emotions_json     JSON         DEFAULT NULL COMMENT '近期情绪列表 JSON数组',
  preferences_json  JSON         DEFAULT NULL COMMENT '用户偏好列表 JSON数组',
  contexts_json     JSON         DEFAULT NULL COMMENT '背景信息列表 JSON数组',
  source_count      INT          DEFAULT 0 COMMENT '累计来源对话数',
  updated_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_user_scene (user_id, scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像（L3 长期记忆）';

-- ───────────────────────────────────────────────
-- Step3: 技能注册中心（Skill Auto-Generation）
-- 说明：Agent 成功执行复杂 ReAct 任务链后，LLM 复盘生成的
--       标准函数代码入库；下次对话注入 System Prompt 直接复用。
-- ───────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS skill_registry (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  name            VARCHAR(128)  NOT NULL COMMENT '技能名（唯一，英文驼峰）',
  description     VARCHAR(500)  DEFAULT '' COMMENT '技能描述/适用场景',
  language        VARCHAR(32)   DEFAULT 'java' COMMENT '代码语言 java/python',
  code            MEDIUMTEXT    COMMENT '生成的函数代码（可直接执行）',
  trigger_prompt  TEXT          COMMENT '触发指令（何时调用、参数怎么传）',
  source_trace    TEXT          COMMENT '来源追溯（触发请求+工具链摘要）',
  usage_count     INT           DEFAULT 0 COMMENT '被使用次数',
  status          TINYINT       DEFAULT 1 COMMENT '状态 1启用 0禁用',
  created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_skill_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技能注册中心（Step3 技能自进化）';

-- ============================================================
-- 附：Milvus user_memory collection（L2 长期事实记忆）由代码自动创建
-- 结构：id(自增PK) / user_id(Int64) / fact(VarChar2048)
--       / source_scene(VarChar64) / ts(Int64) / embedding(FloatVector 1024, COSINE HNSW)
-- ============================================================
