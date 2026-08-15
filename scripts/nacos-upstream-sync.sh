#!/usr/bin/env bash
# =============================================================================
# nacos-upstream-sync.sh
# 动态 upstream 同步器：从 Nacos 拉取 chat-web 健康实例 → 动态生成 Nginx upstream → reload
#
# 用途：实现 web 服务的「弹性伸缩 + 自动负载均衡」
#   - 部署新 web 实例 → 自动注册到 Nacos → 本脚本发现 → 流量自动打上去
#   - 下线/宕机实例 → Nacos 心跳超时摘除 → 本脚本发现 → 流量自动摘掉
#
# 部署位置：主服务器 (Nginx 所在机器) /opt/app/nacos-upstream-sync.sh
# 调度方式：crontab 每分钟执行（见下方 INSTALL 说明）
#
# 依赖：
#   - Nacos 服务发现（chat-web 已启用 nacos-discovery）
#   - curl / jq（jq 用于解析 JSON，缺失时回退到 python3）
#
# 用法：
#   bash nacos-upstream-sync.sh            # 单次执行
#   bash nacos-upstream-sync.sh --force    # 强制 reload（忽略 diff）
#   bash nacos-upstream-sync.sh --dry-run  # 只打印，不写入不 reload
# =============================================================================

set -euo pipefail

# ────────────────────────────── 可配置项 ──────────────────────────────
NACOS_HOST="${NACOS_HOST:-172.23.172.13}"          # Nacos 服务器内网地址
NACOS_PORT="${NACOS_PORT:-8848}"
SERVICE_NAME="${SERVICE_NAME:-chat-web}"           # 要发现的服务名
GROUP_NAME="${GROUP_NAME:-DEFAULT_GROUP}"
OUTPUT_CONF="${OUTPUT_CONF:-/etc/nginx/conf.d/upstream.chat.conf}"
NGINX_MAIN_CONF="${NGINX_MAIN_CONF:-/etc/nginx/nginx.conf}"
LOG_FILE="${LOG_FILE:-/opt/app/logs/nacos-upstream-sync.log}"
FORCE_RELOAD=0
DRY_RUN=0

# ────────────────────────────── 参数解析 ──────────────────────────────
for arg in "$@"; do
    case "$arg" in
        --force)   FORCE_RELOAD=1 ;;
        --dry-run) DRY_RUN=1 ;;
        *) echo "未知参数: $arg" >&2; exit 2 ;;
    esac
done

log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG_FILE"; }

# ────────────────────────────── JSON 解析 ──────────────────────────────
# 优先用 jq，缺失则回退 python3
if command -v jq >/dev/null 2>&1; then
    JSON_PARSER="jq"
elif command -v python3 >/dev/null 2>&1; then
    JSON_PARSER="python3"
else
    log "错误: 需要 jq 或 python3 解析 JSON"
    exit 1
fi

parse_instances() {
    # 输入: Nacos 实例列表 JSON；输出: 每行 "ip:port"
    if [ "$JSON_PARSER" = "jq" ]; then
        echo "$1" | jq -r '.hosts[]? | select(.healthy == true and .enabled == true) | "\(.ip):\(.port)"'
    else
        echo "$1" | python3 -c '
import sys, json
data = json.load(sys.stdin)
for h in data.get("hosts", []):
    if h.get("healthy") and h.get("enabled"):
        print("{}:{}".format(h["ip"], h["port"]))
'
    fi
}

# ────────────────────────────── 拉取实例 ──────────────────────────────
NACOS_URL="http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/ns/instance/list?serviceName=${SERVICE_NAME}&groupName=${GROUP_NAME}"

log "拉取 Nacos 实例列表: ${SERVICE_NAME}"
RESP=$(curl -s --max-time 5 "${NACOS_URL}" || true)

if [ -z "$RESP" ]; then
    log "错误: Nacos 无响应，保留现有 upstream（不破坏流量）"
    exit 0
fi

# 校验返回是否有效（含 hosts 字段）
if ! echo "$RESP" | grep -q '"hosts"'; then
    log "错误: Nacos 返回异常: $(echo "$RESP" | head -c 200)，保留现有 upstream"
    exit 0
fi

INSTANCES=$(parse_instances "$RESP" | sort -u)

if [ -z "$INSTANCES" ]; then
    log "警告: ${SERVICE_NAME} 无健康实例，保留现有 upstream（避免全站 502）"
    exit 0
fi

log "发现健康实例: $(echo "$INSTANCES" | tr '\n' ' ')"

# ────────────────────────────── 生成 upstream 配置 ──────────────────────────────
# 不要粘性：least_conn（最小连接数，长连接 WebSocket 场景负载最均衡）
NEW_CONF="# 由 nacos-upstream-sync.sh 自动生成，请勿手改（每次 sync 会覆盖）
# 来源: Nacos ${NACOS_HOST}:${NACOS_PORT} / ${SERVICE_NAME} (${GROUP_NAME})
# 生成时间: $(date '+%F %T')
upstream backend_nodes {
    least_conn;
"
for inst in $INSTANCES; do
    NEW_CONF+="    server ${inst} max_fails=2 fail_timeout=5s;
"
done
NEW_CONF+="    keepalive 64;
}
"

if [ "$DRY_RUN" = "1" ]; then
    log "=== DRY RUN: 将生成以下配置 ==="
    echo "$NEW_CONF"
    exit 0
fi

# ────────────────────────────── 对比 & 写入 & reload ──────────────────────────────
mkdir -p "$(dirname "$OUTPUT_CONF")"

CHANGED=0
if [ ! -f "$OUTPUT_CONF" ]; then
    CHANGED=1
elif ! diff -q <(echo "$NEW_CONF") "$OUTPUT_CONF" >/dev/null 2>&1; then
    CHANGED=1
fi

if [ "$FORCE_RELOAD" = "1" ]; then
    CHANGED=1
fi

if [ "$CHANGED" = "1" ]; then
    echo "$NEW_CONF" > "$OUTPUT_CONF"
    log "upstream 变更，写入并 reload"
    if nginx -t >/dev/null 2>&1; then
        nginx -s reload
        log "reload 成功"
    else
        log "错误: nginx -t 校验失败，回滚配置"
        if [ -f "${OUTPUT_CONF}.bak" ]; then
            cp "${OUTPUT_CONF}.bak" "$OUTPUT_CONF"
        fi
        nginx -t
        exit 1
    fi
    # 备份当前生效配置
    cp "$OUTPUT_CONF" "${OUTPUT_CONF}.bak"
else
    log "upstream 无变化，跳过 reload"
fi

exit 0
