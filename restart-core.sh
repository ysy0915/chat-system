#!/bin/bash
# chat-core 重启脚本（端口 9090）
# 用法：bash /opt/app/restart-core.sh

PORT=9090
APP_JAR=/opt/app/core/chat-core-0.0.1-SNAPSHOT.jar
LOG_FILE=/opt/app/logs/core-9090.log
PID_FILE=/opt/app/logs/core-9090.pid
HEAP_DUMP=/opt/app/logs/core-heap-dump

echo "[core] 重启开始 $(date)"

# 杀旧进程
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[core] 杀死旧进程 PID=$OLD_PID"
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi
pkill -9 -f 'chat-core' 2>/dev/null

# 等端口释放
for i in $(seq 1 10); do
    if ! ss -tlnp | grep -q ":$PORT "; then
        echo "[core] 端口 $PORT 已释放"
        break
    fi
    echo "[core] 端口 $PORT 仍被占用，等待... ($i/10)"
    sleep 2
done

# 启动
nohup java \
    -Xms256m -Xmx768m \
    -Xss512k \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=$HEAP_DUMP \
    -XX:+ExitOnOutOfMemoryError \
    -jar "$APP_JAR" \
    --spring.profiles.active=prod \
    --server.port=9090 \
    --spring.application.name=chat-core \
    --spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 \
    --spring.cloud.nacos.discovery.ip=172.23.172.13 \
    --spring.cloud.nacos.discovery.enabled=true \
    > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "[core] 启动中 PID=$(cat $PID_FILE)"

# 等待健康检查
for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$PORT/actuator/health 2>/dev/null | grep -q '200'; then
        echo "[core] 启动成功 (第 ${i} 次检测) $(date)"
        exit 0
    fi
    sleep 3
done
echo "[core] 启动超时！请检查日志: $LOG_FILE"
exit 1
