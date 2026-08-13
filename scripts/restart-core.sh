#!/bin/bash
# chat-core 重启脚本（双实例 端口 9090/9092）
# 说明：9091 已被 milvus-standalone 容器占用，第二实例使用 9092
# 用法：bash /opt/app/restart-core.sh [9090|9092|all]
# 不传参数默认重启全部两个实例
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a

PORT=${1:-all}
APP_JAR=/opt/app/core/chat-core-0.0.1-SNAPSHOT.jar

restart_one() {
    local P=$1
    local LOG_FILE=/opt/app/logs/core-${P}.log
    local PID_FILE=/opt/app/logs/core-${P}.pid
    local HEAP_DUMP=/opt/app/logs/core-${P}-heap-dump
    # 9090 主实例保留 Xmx768m（避免历史 OOM 回归）；9092 从实例 Xmx512m 节约内存
    local XMX=512m
    if [ "$P" = "9090" ]; then
        XMX=768m
    fi

    echo "[core-$P] 重启开始 $(date)"

    # 1. PID 文件杀进程
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat "$PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[core-$P] 杀死旧进程 PID=$OLD_PID"
            kill "$OLD_PID" 2>/dev/null
            sleep 3
            kill -9 "$OLD_PID" 2>/dev/null
        fi
        rm -f "$PID_FILE"
    fi

    # 2. pkill 兜底（按 server.port 匹配，不误杀另一实例）
    pkill -9 -f "chat-core-0.0.1-SNAPSHOT.jar.*server.port=${P}" 2>/dev/null
    sleep 1

    # 3. 端口强杀
    for i in $(seq 1 15); do
        PORT_PID=$(ss -tlnp | grep ":$P " | grep -oP 'pid=\K\d+' | head -1)
        if [ -z "$PORT_PID" ]; then
            echo "[core-$P] 端口 $P 已释放"
            break
        fi
        echo "[core-$P] 端口 $P 仍被 PID=$PORT_PID 占用，强制杀死 ($i/15)"
        kill -9 "$PORT_PID" 2>/dev/null
        sleep 2
    done

    # 启动
    nohup java \
        -Xms${XMX} -Xmx${XMX} \
        -Xss512k \
        -XX:+UseG1GC \
        -XX:MaxGCPauseMillis=200 \
        -XX:G1HeapRegionSize=4m \
        -XX:InitiatingHeapOccupancyPercent=35 \
        -XX:ParallelGCThreads=4 \
        -XX:ConcGCThreads=2 \
        -XX:+HeapDumpOnOutOfMemoryError \
        -XX:HeapDumpPath=$HEAP_DUMP \
        -XX:+ExitOnOutOfMemoryError \
        -XX:+UseContainerSupport \
        -Xlog:gc*:file=/opt/app/logs/gc-core-${P}.log:time,uptime,level,tags:filecount=5,filesize=10m \
        -DLOG_PATH=/opt/app/logs \
        -jar "$APP_JAR" \
        --spring.profiles.active=prod \
        --server.port=${P} \
        --spring.application.name=chat-core \        > "$LOG_FILE" 2>&1 &

    echo $! > "$PID_FILE"
    echo "[core-$P] 启动中 PID=$(cat $PID_FILE)"

    # 等待健康检查
    for i in $(seq 1 20); do
        if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$P/actuator/health 2>/dev/null | grep -q '200'; then
            echo "[core-$P] 启动成功 (第 ${i} 次检测) $(date)"
            return 0
        fi
        sleep 3
    done
    echo "[core-$P] 启动超时！请检查日志: $LOG_FILE"
    return 1
}

if [ "$PORT" = "all" ]; then
    restart_one 9090
    restart_one 9092
else
    restart_one "$PORT"
fi
