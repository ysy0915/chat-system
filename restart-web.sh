#!/bin/bash
# chat-web 重启脚本（端口 8081/8082）
# 用法：bash /opt/app/restart-web.sh [8081|8082|all]
# 不传参数默认重启全部两个实例

PORT=${1:-all}
APP_JAR=/opt/app/web/chat-web-0.0.1-SNAPSHOT.jar

restart_one() {
    local P=$1
    local LOG_FILE=/opt/app/logs/web-${P}.log
    local PID_FILE=/opt/app/logs/web-${P}.pid

    echo "[web-$P] 重启开始 $(date)"

    # 杀旧进程
    if [ -f "$PID_FILE" ]; then
        OLD_PID=$(cat "$PID_FILE")
        if kill -0 "$OLD_PID" 2>/dev/null; then
            echo "[web-$P] 杀死旧进程 PID=$OLD_PID"
            kill "$OLD_PID"
            sleep 3
            kill -9 "$OLD_PID" 2>/dev/null
        fi
    fi

    # 等端口释放
    for i in $(seq 1 10); do
        if ! ss -tlnp | grep -q ":$P "; then
            echo "[web-$P] 端口 $P 已释放"
            break
        fi
        echo "[web-$P] 端口 $P 仍被占用，等待... ($i/10)"
        sleep 2
    done

    # 启动
    nohup java \
        -Xms128m -Xmx256m \
        -Xss512k \
        -XX:+UseG1GC \
        -XX:+HeapDumpOnOutOfMemoryError \
        -XX:HeapDumpPath=/opt/app/logs/web-${P}-heap-dump \
        -XX:+ExitOnOutOfMemoryError \
        -jar "$APP_JAR" \
        --spring.profiles.active=prod \
        --server.port=${P} \
        --spring.application.name=chat-web \
        --spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 \
        --spring.cloud.nacos.discovery.ip=172.23.172.13 \
        --spring.cloud.nacos.discovery.enabled=true \
        --app.core.base-url=http://127.0.0.1:9090 \
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
    restart_one $PORT
fi
