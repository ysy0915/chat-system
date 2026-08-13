#!/usr/bin/env bash
# ============================================================
# [B档] LLM 配置三源归一后：环境变量 upsert 脚本改写入 llm_* 新表
# （旧表 model_configs 已退役，不再写入）
#
# 用法：
#   export DEEPSEEK_API_KEY=... QWEN_API_KEY=... DOUBAO_API_KEY=...
#   export MYSQL_HOST=... MYSQL_USER=... MYSQL_PWD=... MYSQL_DB=test_data
#   bash scripts/insert_model_configs_from_env.sh
#
# 前置：docs/sql/llm_routing_schema.sql 已执行，新表已建。
# ============================================================
set -euo pipefail

MYSQL_HOST=${MYSQL_HOST:-127.0.0.1}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_PWD=${MYSQL_PWD:-19641025}
MYSQL_DB=${MYSQL_DB:-test_data}

required=(DEEPSEEK_API_KEY QWEN_API_KEY DOUBAO_API_KEY)
missing=()
for v in "${required[@]}"; do
  if [ -z "${!v:-}" ]; then
    missing+=("$v")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "Missing environment variables: ${missing[*]}"
  echo "Please export them and re-run. Example: export DEEPSEEK_API_KEY=..."
  exit 1
fi

encrypt_if_needed() {
  local val="$1"
  if [ -n "${APP_MASTER_KEY:-}" ]; then
    echo -n "$val" | openssl enc -aes-256-cbc -a -salt -pass pass:"${APP_MASTER_KEY}"
  else
    echo -n "$val"
  fi
}

DEEPSEEK_BASE_URL=${DEEPSEEK_BASE_URL:-https://api.deepseek.com/v1}
QWEN_BASE_URL=${QWEN_BASE_URL:-https://dashscope.aliyuncs.com/compatible-mode/v1}
DOUBAO_BASE_URL=${DOUBAO_BASE_URL:-https://ark.cn-beijing.volces.com/api/v3}

deepseek_key_enc=$(encrypt_if_needed "${DEEPSEEK_API_KEY}")
qwen_key_enc=$(encrypt_if_needed "${QWEN_API_KEY}")
doubao_key_enc=$(encrypt_if_needed "${DOUBAO_API_KEY}")

# escape single quotes for SQL
escape_sql(){ printf '%s' "$1" | sed "s/'/''/g"; }

ks_deepseek=$(escape_sql "$deepseek_key_enc")
ks_qwen=$(escape_sql "$qwen_key_enc")
ks_doubao=$(escape_sql "$doubao_key_enc")
bs_deepseek=$(escape_sql "$DEEPSEEK_BASE_URL")
bs_qwen=$(escape_sql "$QWEN_BASE_URL")
bs_doubao=$(escape_sql "$DOUBAO_BASE_URL")

run_sql() {
  local sql="$1"
  mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PWD" "$MYSQL_DB" -e "$sql"
}

# upsert 提供商（uk_provider_name 幂等），返回 provider_config_id
upsert_provider() {
  local name="$1"; shift
  local base_url="$1"; shift
  local keyval="$1"; shift
  local provider_id
  provider_id=$(mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PWD" -D"$MYSQL_DB" -N -s -e \
    "SELECT id FROM llm_provider_config WHERE provider_name='${name}' LIMIT 1;" || true)
  if [ -z "${provider_id}" ]; then
    run_sql "INSERT INTO llm_provider_config (provider_name, base_url, auth_type, invoke_type, enabled, is_default, priority, description) VALUES ('${name}','${base_url}','api_key','rest',1,0,100,'环境变量脚本 upsert');"
    provider_id=$(mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PWD" -D"$MYSQL_DB" -N -s -e \
      "SELECT id FROM llm_provider_config WHERE provider_name='${name}' LIMIT 1;")
  else
    run_sql "UPDATE llm_provider_config SET base_url='${base_url}', enabled=1, updated_at=NOW() WHERE id=${provider_id};"
  fi
  # llm_provider_props 无唯一键：先删该提供商旧 api_key 再插，保证幂等
  run_sql "DELETE FROM llm_provider_props WHERE provider_config_id=${provider_id} AND prop_key='api_key';"
  run_sql "INSERT INTO llm_provider_props (provider_config_id, prop_key, prop_value, prop_type, description) VALUES (${provider_id},'api_key','${keyval}','SECRET','环境变量脚本 upsert');"
  echo "${provider_id}"
}

# upsert 模型（uk_provider_model 幂等）
upsert_model() {
  local provider_id="$1"; shift
  local model="$1"; shift
  run_sql "INSERT INTO llm_model_config (provider_config_id, model_name, display_name, model_type, max_tokens, enabled, is_default, priority, description) VALUES (${provider_id},'${model}','${model}','chat',4096,1,0,100,'环境变量脚本 upsert') ON DUPLICATE KEY UPDATE enabled=1, priority=100, updated_at=NOW();"
  echo "upsert model=${model} (provider_id=${provider_id})"
}

echo "== upsert providers =="
DP=$(upsert_provider "deepseek" "${bs_deepseek}" "${ks_deepseek}")
QP=$(upsert_provider "qwen"     "${bs_qwen}"     "${ks_qwen}")
BO=$(upsert_provider "doubao"   "${bs_doubao}"   "${ks_doubao}")

echo "== upsert models =="
upsert_model "${DP}" "deepseek-chat"
upsert_model "${QP}" "qwen-plus"
upsert_model "${BO}" "doubao-seed-evolving"

echo "Upsert completed for deepseek, qwen, doubao (new llm_* tables)."
if [ -n "${APP_MASTER_KEY:-}" ]; then
  echo "API keys encrypted with APP_MASTER_KEY before storing."
else
  echo "Warning: API keys stored in DB in plaintext. Set APP_MASTER_KEY to enable encryption."
fi
