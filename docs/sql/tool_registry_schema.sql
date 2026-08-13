-- ============================================================
-- 工具平台化注册表表设计  (v1)
--
-- [B档] 状态说明（2026-08-13）：
--   ✅ 在用：tool_registry —— 工具元数据声明注册表
--      —— 代码内置工具（source=CODE）启动时自动注册默认声明；
--        管理面（/internal/tools）可将声明落库（source=DB），
--        覆盖代码默认的 enabled / description / parameters / scope，
--        无需发版即可开关工具 / 调整 AI 可见的提示词。
--      —— 运行时（ToolRegistry.getToolsSchema / getTool）仅暴露 enabled 工具。
--
-- 设计思想：
--   tool_name   → 工具唯一名（与 Tool.getName() 一致）
--   description → AI 可见的工具描述（可覆盖）
--   parameters  → AI 可见的参数 JSON Schema（可覆盖，NULL=用代码默认）
--   enabled     → 启用开关（0=禁用，LLM 不可见不可调用）
--   scope       → 可见范围（* = 全部 / chat / subagent / 自定义场景，预留）
--   source      → 声明来源：CODE（代码内置）/ DB（管理面声明）
--
-- ⚠️ 警告：本表为可选增强。未建表时 ToolRegistry 容错降级
--   （按代码默认声明注册继续），不影响既有工具功能。
-- ============================================================

-- ─── 工具注册表 ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS tool_registry (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    tool_name   VARCHAR(64)  NOT NULL COMMENT '工具唯一名（与 Tool.getName() 一致）',
    description VARCHAR(1024) DEFAULT '' COMMENT 'AI 可见的工具描述（可被管理面覆盖）',
    parameters  TEXT          DEFAULT NULL COMMENT 'AI 可见的参数 JSON Schema（NULL=用代码默认）',
    enabled     TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用（0=禁用，LLM 不可见不可调用）',
    scope       VARCHAR(64)   NOT NULL DEFAULT '*' COMMENT '可见范围: * / chat / subagent / 自定义',
    source      VARCHAR(16)   NOT NULL DEFAULT 'DB' COMMENT '声明来源: CODE(代码内置) / DB(管理面声明)',
    created_at  DATETIME      DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tool_name (tool_name),
    INDEX idx_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='工具平台化注册表（元数据声明）';
