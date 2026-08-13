#!/bin/bash
# ============================================================
# AI SRE 智能监控告警（本地化Harness AI SRE等效方案）
# 定时检查系统健康，异常时自动告警+根因分析
# 建议加到crontab: * * * * * bash /opt/app/sre-monitor.sh
# ============================================================

MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MAIN_PEM="/Users/apple/Downloads/我的密钥.pem"
MILVUS_SERVER="root@121.40.188.98"
MAIN_SERVER="root@112.124.106.108"
LOG_FILE="/opt/app/logs/sre-monitor.log"
ALERT_FILE="/opt/app/logs/sre-alerts.log"

mkdir -p /opt/app/logs

log()    { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE"; }
alert()  { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ALERT] $1" >> "$ALERT_FILE"; echo "[ALERT] $1"; }

# ---------- 1. 服务健康检查 ----------
check_services() {
    for port_name in "9090:core" "8081:web" "8083:games" "8084:media"; do
        port=$(echo $port_name | cut -d: -f1)
        name=$(echo $port_name | cut -d: -f2)
        code=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 3 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null" || echo "000")
        if [ "$code" != "200" ]; then
            alert "$name (port $port) 不健康: HTTP $code"
            # 根因分析
            analyze_failure $name $port
        else
            log "$name (port $port) OK"
        fi
    done
}

# ---------- 2. 根因分析 ----------
analyze_failure() {
    local name=$1 port=$2
    log "  分析 $name 失败原因..."

    # 检查进程是否存在
    PID=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "pgrep -f 'java.*chat-$name'" 2>/dev/null)
    if [ -z "$PID" ]; then
        log "  → 进程不存在，可能OOM被杀"
        # 检查OOM日志
        OOM=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "dmesg | grep 'Out of memory' | tail -1" 2>/dev/null)
        if [ -n "$OOM" ]; then
            alert "  → 根因: OOM Killer杀了进程: $OOM"
        fi
        # 自动恢复
        log "  → 尝试自动恢复..."
        ssh -i "$MILVUS_PEM" $MILVUS_SERVER "bash /opt/app/restart-$name.sh" 2>/dev/null
        return
    fi

    # 检查端口是否监听
    LISTEN=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "ss -tlnp | grep ':$port '" 2>/dev/null)
    if [ -z "$LISTEN" ]; then
        log "  → 进程存在但端口未监听，可能启动中或端口冲突"
        return
    fi

    # 检查最近错误日志
    ERRORS=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "grep -i 'error\|exception' /opt/app/logs/chat-$name.log 2>/dev/null | tail -3")
    if [ -n "$ERRORS" ]; then
        log "  → 最近错误日志:"
        echo "$ERRORS" | while read line; do log "    $line"; done
    fi
}

# ---------- 3. 内存检查 ----------
check_memory() {
    MEM_INFO=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "free -m | awk '/Mem/{print \$2\"|\"\$3}'")
    TOTAL=$(echo $MEM_INFO | cut -d'|' -f1)
    USED=$(echo $MEM_INFO | cut -d'|' -f2)
    PCT=$((USED * 100 / TOTAL))
    if [ $PCT -gt 90 ]; then
        alert "Milvus服务器内存使用率 ${PCT}% (${USED}M/${TOTAL}M)，可能OOM"
        # 半自动降级建议：games 是低优先级服务，停掉可释放 ~536MB
        alert "💡【降级建议】高峰/内存告警可执行: ssh -i Milvus.pem root@121.40.188.98 \"bash /opt/app/stop-games.sh\"（释放约 536MB，恢复: restart-games.sh）"
    elif [ $PCT -gt 80 ]; then
        log "内存使用率 ${PCT}%，偏高"
    fi
}

# ---------- 4. 磁盘检查 ----------
check_disk() {
    for server_pem in "$MILVUS_PEM:$MILVUS_SERVER:Milvus" "$MAIN_PEM:$MAIN_SERVER:主"; do
        PEM=$(echo $server_pem | cut -d: -f1)
        SRV=$(echo $server_pem | cut -d: -f2)
        NAME=$(echo $server_pem | cut -d: -f3)
        PCT=$(ssh -i "$PEM" $SRV "df / | awk 'NR==2{print \$5}'" 2>/dev/null | tr -d '%')
        if [ "$PCT" -gt 90 ]; then
            alert "$NAME服务器磁盘使用率 ${PCT}%，需要清理"
        fi
    done
}

# ---------- 5. RabbitMQ队列堆积检查 ----------
check_rabbitmq() {
    QUEUES=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "rabbitmqctl list_queues name messages 2>/dev/null | grep -v 'Listing\|Timeout'")
    echo "$QUEUES" | while read queue msgs; do
        [ -z "$queue" ] && continue
        if [ "$msgs" -gt 1000 ]; then
            alert "RabbitMQ队列 $queue 堆积 ${msgs} 条消息"
        fi
    done
}

# ---------- 6. GC异常检测 ----------
check_gc() {
    # 检查core的GC停顿是否异常（>500ms）
    RECENT_GC=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "grep 'Pause' /opt/app/logs/gc-core.log 2>/dev/null | tail -5")
    echo "$RECENT_GC" | while read line; do
        PAUSE_MS=$(echo "$line" | grep -oP '\d+ms' | head -1 | tr -d 'ms')
        if [ -n "$PAUSE_MS" ] && [ "$PAUSE_MS" -gt 500 ]; then
            alert "core GC停顿 ${PAUSE_MS}ms，影响响应延迟"
        fi
    done
}

# ---------- 7. 变更检测 ----------
check_recent_deploy() {
    # 检查最近5分钟内是否有部署（jar文件被修改）
    RECENT=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "find /opt/app -name '*.jar' -mmin -5 2>/dev/null | head -1")
    if [ -n "$RECENT" ]; then
        log "检测到最近部署: $RECENT"
    fi
}

# ---------- 执行 ----------
log "===== SRE监控巡检 ====="
check_services
check_memory
check_disk
check_rabbitmq
check_gc
check_recent_deploy
log "===== 巡检完成 ====="
