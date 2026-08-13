#!/bin/bash
# chat-web 重启脚本（双实例 端口 8081/8082）
# 用法：bash /opt/app/restart-web.sh [8081|8082|all]
# 不传参数默认重启全部两个实例
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a

PORT=${1:-all}
APP_JAR=/opt/app/web/chat-web-0.0.1-SNAPSHOT.jar

restart_one() {
    local P=$1
    local LOG_FILE=/opt/app/logs/web-${P}.log
    local PID_FILE=/opt/app/logs/web-${P}.pid

    echo "[web-$P] 重启开始 $(date)"

    # 1. PID 文件杀进程
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat "$PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[web-$P] 杀死旧进程 PID=$OLD_PID"
            kill "$OLD_PID" 2>/dev/null
            sleep 3
            kill -9 "$OLD_PID" 2>/dev/null
        fi
        rm -f "$PID_FILE"
    fi

    # 2. pkill 兜底（按端口匹配，不误杀另一实例）
    pkill -9 -f "chat-web.*server.port=${P}" 2>/dev/null
    sleep 1

    # 3. 端口强杀
    for i in $(seq 1 15); do
        PORT_PID=$(ss -tlnp | grep ":$P " | grep -oP 'pid=\K\d+' | head -1)
        if [ -z "$PORT_PID" ]; then
            echo "[web-$P] 端口 $P 已释放"
            break
        fi
        echo "[web-$P] 端口 $P 仍被 PID=$PORT_PID 占用，强制杀死 ($i/15)"
        kill -9 "$PORT_PID" 2>/dev/null
        sleep 2
    done

    # 启动
    nohup java \
        -Xms256m -Xmx256m \
        -Xss512k \
        -XX:+UseG1GC \
        -XX:MaxGCPauseMillis=200 \
        -XX:G1HeapRegionSize=2m \
        -XX:InitiatingHeapOccupancyPercent=45 \
        -XX:ParallelGCThreads=2 \
        -XX:ConcGCThreads=1 \
        -XX:+HeapDumpOnOutOfMemoryError \
        -XX:HeapDumpPath=/opt/app/logs/web-${P}-heap-dump \
        -XX:+ExitOnOutOfMemoryError \
        -XX:+UseContainerSupport \
        -Xlog:gc*:file=/opt/app/logs/gc-web-${P}.log:time,uptime,level,tags:filecount=5,filesize=10m \
        -DLOG_PATH=/opt/app/logs \
        -jar "$APP_JAR" \
        --spring.profiles.active=prod \
        --server.port=${P} \
        --spring.application.name=chat-web \
        --app.core.base-url=http://127.0.0.1:9090 \
        --app.core.base-urls=http://127.0.0.1:9090,http://127.0.0.1:9092 \
        > "$LOG_FILE" 2>&1 &

    echo $! > "$PID_FILE"
    echo "[web-$P] 启动中 PID=$(cat $PID_FILE)"

    # 等待健康检查
    for i in $(seq 1 20); do
        if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$P/actuator/health 2>/dev/null | grep -q '200'; then
            echo "[web-$P] 启动成功 (第 ${i} 次检测) $(date)"
            return 0
        fi
        sleep 3
    done
    echo "[web-$P] 启动超时！请检查日志: $LOG_FILE"
    return 1
}

if [ "$PORT" = "all" ]; then
    restart_one 8081
    restart_one 8082
else
    restart_one "$PORT"
fi
