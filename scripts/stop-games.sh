#!/bin/bash
# =====================================================================
# chat-games 优雅停止（高峰降级释放内存）
# 背景：服务器可用内存紧张（~450MB），games 是低优先级非核心服务，
#      高峰/内存告警时停掉可释放 ~536MB（RSS），保障 core/web/llm 稳定。
# 恢复：bash /opt/app/restart-games.sh
# 用法：bash /opt/app/stop-games.sh [--force]
#   --force  优雅停机等待 30s 后强制 kill -9（默认等待即强杀）
# =====================================================================
PORT=8083
PID_FILE=/opt/app/logs/games-8083.pid

stop_pid() {
    local pid="$1"
    if ! kill -0 "$pid" 2>/dev/null; then
        echo "[games] PID=$pid 已不存在"
        return 0
    fi
    # 先 SIGTERM：Spring 触发 shutdown hook → Nacos 自动注销实例
    echo "[games] SIGTERM 已发送 PID=$pid，等待优雅停机（最多 30s）..."
    kill "$pid"
    for i in $(seq 1 15); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "[games] 优雅停机完成 ($(date +%H:%M:%S))"
            return 0
        fi
        sleep 2
    done
    if [ "${1:-}" = "--force" ]; then
        echo "[games] 优雅停机超时，kill -9 强制结束"
        kill -9 "$pid" 2>/dev/null
        return 0
    fi
    echo "[games] 优雅停机超时，请检查 PID=$pid 或用 --force"
    return 1
}

echo "[games] 停止开始 $(date '+%F %T')"

stopped=0
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    stop_pid "$PID" "$1" && stopped=1
else
    echo "[games] 无 PID 文件，按端口 $PORT 兜底查找"
    PID=$(ss -tlnp | grep ":$PORT " | grep -oE 'pid=[0-9]+' | head -1 | cut -d= -f2)
    [ -n "$PID" ] && stop_pid "$PID" "$1" && stopped=1
fi

# 确认端口已释放
for i in $(seq 1 10); do
    if ! ss -tlnp | grep -q ":$PORT "; then
        echo "[games] 端口 $PORT 已释放"
        break
    fi
    echo "[games] 端口 $PORT 仍占用，等待... ($i/10)"
    sleep 2
done

rm -f "$PID_FILE"

if [ "$stopped" -eq 1 ] || ! ss -tlnp | grep -q ":$PORT "; then
    # 写主动降级标记：通知 health-check.sh 跳过 games 自动重启，保持降级状态
    touch /opt/app/logs/games-degraded
    echo "[games] 已写入降级标记 /opt/app/logs/games-degraded（health-check 将跳过自动重启）"
    echo "[games] 已停止 ($(date '+%F %T'))，释放约 500+MB 内存"
    echo "[games] 恢复: bash /opt/app/restart-games.sh"
    exit 0
fi
echo "[games] 停止失败：进程不存在或端口被其他程序占用"
exit 1
