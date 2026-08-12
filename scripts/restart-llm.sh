#!/bin/bash
# chat-llm 重启脚本（双实例 端口 9095/9096，gRPC 9195/9196，Nacos 注册）
# 用法：bash /opt/app/restart-llm.sh [9095|9096|all]
# 不传参数默认重启全部两个实例
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a

PORT=${1:-all}
APP_JAR=/opt/app/llm/chat-llm-0.0.1-SNAPSHOT.jar

restart_one() {
    local P=$1
    local GP=$((P + 100))   # gRPC 端口：9095→9195, 9096→9196
    local LOG_FILE=/opt/app/logs/llm-${P}.log
    local PID_FILE=/opt/app/logs/llm-${P}.pid

    echo "[llm-$P] 重启开始 $(date)"

    # 1. PID 文件杀进程
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat "$PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[llm-$P] 杀死旧进程 PID=$OLD_PID"
            kill "$OLD_PID" 2>/dev/null
            sleep 2
            kill -9 "$OLD_PID" 2>/dev/null
        fi
        rm -f "$PID_FILE"
    fi

    # 2. pkill 兜底（按端口匹配，不误杀另一实例）
    pkill -9 -f "chat-llm.*server.port=${P}" 2>/dev/null
    sleep 1

    # 3. 端口强杀
    for i in $(seq 1 15); do
        PORT_PID=$(ss -tlnp | grep ":$P " | grep -oP 'pid=\K\d+' | head -1)
        if [ -z "$PORT_PID" ]; then
            echo "[llm-$P] 端口 $P 已释放"
            break
        fi
        echo "[llm-$P] 端口 $P 仍被 PID=$PORT_PID 占用，强制杀死 ($i/15)"
        kill -9 "$PORT_PID" 2>/dev/null
        sleep 2
    done

    # 启动
    nohup java \
        -Xms256m -Xmx512m \
        -Xss512k \
        -XX:+UseG1GC \
        -XX:MaxGCPauseMillis=200 \
        -XX:+HeapDumpOnOutOfMemoryError \
        -XX:HeapDumpPath=/opt/app/logs/llm-${P}-heap-dump \
        -XX:+ExitOnOutOfMemoryError \
        -XX:+UseContainerSupport \
        -Xlog:gc*:file=/opt/app/logs/gc-llm-${P}.log:time,uptime,level,tags:filecount=5,filesize=10m \
        -DLOG_PATH=/opt/app/logs \
        -jar "$APP_JAR" \
        --spring.profiles.active=prod \
        --server.port=${P} \
        --grpc.server.port=${GP} \
        --spring.application.name=chat-llm \
        --spring.datasource.url="$DB_URL" \
        --spring.datasource.username="$DB_USERNAME" \
        --spring.datasource.password="$DB_PASSWORD" \
        --spring.data.redis.host=172.18.160.222 \
        --spring.data.redis.port=6379 \
        --spring.cloud.nacos.discovery.enabled=true \
        --spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 \
        --spring.cloud.nacos.discovery.ip=172.23.172.13 \
        --spring.cloud.consul.enabled=false \
        > "$LOG_FILE" 2>&1 &

    echo $! > "$PID_FILE"
    echo "[llm-$P] 启动中 PID=$(cat $PID_FILE) gRPC=${GP}"

    # 等待健康检查
    for i in $(seq 1 30); do
        if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$P/actuator/health 2>/dev/null | grep -q '200'; then
            echo "[llm-$P] 启动成功 (第 ${i} 次检测) $(date)"
            return 0
        fi
        sleep 3
    done
    echo "[llm-$P] 启动超时！请检查日志: $LOG_FILE"
    return 1
}

if [ "$PORT" = "all" ]; then
    restart_one 9095
    restart_one 9096
else
    restart_one "$PORT"
fi
