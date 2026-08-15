#!/usr/bin/env bash
# =============================================================================
# web-scale.sh
# web 服务弹性扩缩容控制器（配合 Nacos 服务发现 + nacos-upstream-sync.sh 自动负载）
#
# 用法（在 Milvus 服务器 121.40.188.98 上执行）：
#   bash web-scale.sh scale-up          # 扩容到 2 实例（8081 + 8082）
#   bash web-scale.sh scale-down        # 缩容到 1 实例（仅 8081）
#   bash web-scale.sh status            # 查看当前实例状态
#
# 原理：
#   - scale-down: 写入 /opt/app/logs/web-8082-disabled 标记 + 优雅停 8082
#                 → health-check.sh 检测到标记不再拉起 8082
#                 → Nacos 心跳超时自动摘除 8082
#                 → 主服务器 nacos-upstream-sync.sh 发现后自动从 upstream 移除
#   - scale-up:   删除标记 → health-check.sh 下次自动拉起 8082
#                 → Nacos 自动注册 → 主服务器自动把流量打上去
#
# 注意：扩缩容后约 1 分钟生效（health-check.sh + nacos-upstream-sync.sh 均为分钟级 cron）
# =============================================================================

set -euo pipefail

DISABLED_MARK="/opt/app/logs/web-8082-disabled"
HEALTH_SCRIPT="/opt/app/health-check.sh"

log() { echo "[web-scale $(date '+%F %T')] $*"; }

port_listening() {
    local port=$1
    ss -tlnp 2>/dev/null | grep -q ":${port} " || \
    netstat -tlnp 2>/dev/null | grep -q ":${port} "
}

scale_down() {
    log "缩容：web 双实例 → 单实例（保留 8081，下线 8082）"
    # 1) 写入缩容标记（让 health-check.sh 不再守护 8082）
    touch "$DISABLED_MARK"
    log "已写入缩容标记 $DISABLED_MARK"

    # 2) 优雅停止 8082
    local pid
    pid=$(ss -tlnp 2>/dev/null | grep ':8082 ' | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
    if [ -n "$pid" ]; then
        log "优雅停止 8082 (PID $pid)..."
        kill "$pid" 2>/dev/null || true
        sleep 8
        # 仍未退出则强杀
        if port_listening 8082; then
            log "8082 未优雅退出，强制 kill -9"
            kill -9 "$pid" 2>/dev/null || true
        fi
    else
        log "8082 未在运行"
    fi

    log "缩容完成。Nacos 约 15-30 秒摘除实例，主服务器约 1 分钟内从 upstream 移除流量"
}

scale_up() {
    log "扩容：web 单实例 → 双实例（8081 + 8082）"
    # 1) 删除缩容标记（恢复 health-check.sh 守护）
    rm -f "$DISABLED_MARK"
    log "已删除缩容标记"

    # 2) 立即启动 8082（不等待下一次 cron）
    if port_listening 8082; then
        log "8082 已在运行"
    else
        log "启动 8082..."
        bash /opt/app/restart-web.sh 8082 || log "restart-web.sh 8082 返回非零，可能由 health-check 稍后拉起"
    fi

    log "扩容完成。Nacos 约 15-30 秒注册实例，主服务器约 1 分钟内把流量打上去"
}

status() {
    echo "=== web 实例状态 ==="
    if port_listening 8081; then
        echo "8081: 运行中 ✅"
    else
        echo "8081: 未运行 ❌"
    fi
    if port_listening 8082; then
        echo "8082: 运行中 ✅"
    else
        echo "8082: 未运行 ⏸️"
    fi
    if [ -f "$DISABLED_MARK" ]; then
        echo "缩容标记: 已设置（health-check 不会拉起 8082）"
    else
        echo "缩容标记: 未设置（health-check 会守护 8082）"
    fi
    echo ""
    echo "=== Nacos 注册实例 ==="
    curl -s --max-time 5 "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=chat-web" 2>/dev/null \
        | python3 -c '
import sys, json
try:
    data = json.load(sys.stdin)
    for h in data.get("hosts", []):
        print(f"  {h[\"ip\"]}:{h[\"port\"]} healthy={h.get(\"healthy\")} enabled={h.get(\"enabled\")}")
except Exception as e:
    print("  (无法获取 Nacos 实例)", e)
'
}

case "${1:-}" in
    scale-up)   scale_up ;;
    scale-down) scale_down ;;
    status)     status ;;
    *) echo "用法: bash web-scale.sh [scale-up|scale-down|status]"; exit 2 ;;
esac
