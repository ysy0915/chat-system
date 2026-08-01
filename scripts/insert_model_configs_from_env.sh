#!/usr/bin/env bash
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

meta_deepseek=$(printf '%s' "{\"base_url\":\"$DEEPSEEK_BASE_URL\"}")
meta_qwen=$(printf '%s' "{\"base_url\":\"$QWEN_BASE_URL\"}")
meta_doubao=$(printf '%s' "{\"base_url\":\"$DOUBAO_BASE_URL\"}")

# escape single quotes for SQL
escape_sql(){ printf '%s' "$1" | sed "s/'/''/g"; }

ks_deepseek=$(escape_sql "$deepseek_key_enc")
ks_qwen=$(escape_sql "$qwen_key_enc")
ks_doubao=$(escape_sql "$doubao_key_enc")
ms_deepseek=$(escape_sql "$meta_deepseek")
ms_qwen=$(escape_sql "$meta_qwen")
ms_doubao=$(escape_sql "$meta_doubao")

run_sql() {
  local sql="$1"
  mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PWD" "$MYSQL_DB" -e "$sql"
}

upsert_model() {
  local provider="$1"; shift
  local model="$1"; shift
  local keyval="$1"; shift
  local metav="$1"; shift

  # check existing
  local exist_id
  exist_id=$(mysql -h"$MYSQL_HOST" -u"$MYSQL_USER" -p"$MYSQL_PWD" -D"$MYSQL_DB" -N -s -e "SELECT id FROM model_configs WHERE provider='${provider}' AND model='${model}' LIMIT 1;" || true)
  if [ -n "${exist_id}" ]; then
    echo "Updating existing model_configs id=${exist_id} provider=${provider} model=${model}"
    run_sql "UPDATE model_configs SET api_key_encrypted='${keyval}', meta='${metav}', priority=100, enabled=1, updated_at=NOW() WHERE id=${exist_id};"
  else
    echo "Inserting new model_configs provider=${provider} model=${model}"
    run_sql "INSERT INTO model_configs (provider, model, api_key_encrypted, meta, priority, enabled, created_at) VALUES ('${provider}','${model}','${keyval}','${metav}',100,1,NOW());"
  fi
}

upsert_model "deepseek" "deepseek-chat" "${ks_deepseek}" "${ms_deepseek}"
upsert_model "qwen" "qwen-plus" "${ks_qwen}" "${ms_qwen}"
upsert_model "doubao" "doubao-seed-evolving" "${ks_doubao}" "${ms_doubao}"

echo "Upsert completed for deepseek, qwen, doubao."
if [ -n "${APP_MASTER_KEY:-}" ]; then
  echo "API keys encrypted with APP_MASTER_KEY before storing."
else
  echo "Warning: API keys stored in DB in plaintext. Set APP_MASTER_KEY to enable encryption."
fi
