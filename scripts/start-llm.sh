#!/bin/bash
# chat-llm 启动脚本（Milvus 服务器，端口 9095/9096，gRPC 9195/9196，Nacos 注册）
# 用法：bash /opt/app/start-llm.sh [9095|9096]
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a
APP_JAR=/opt/app/llm/chat-llm-0.0.1-SNAPSHOT.jar
PORT=${1:-9095}
GRPC_PORT=$((PORT + 100))
LOG_FILE=/opt/app/logs/llm-${PORT}.log
PID_FILE=/opt/app/logs/llm-${PORT}.pid

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi

nohup java \
    -Xms256m -Xmx512m \
    -Xss512k \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/opt/app/logs/llm-${PORT}-heap-dump \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseContainerSupport \
    -DLOG_PATH=/opt/app/logs \
    -jar "$APP_JAR" \
    --spring.profiles.active=prod \
    --server.port=${PORT} \
    --grpc.server.port=${GRPC_PORT} \
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
echo "llm started PID=$(cat $PID_FILE) PORT=${PORT} gRPC=${GRPC_PORT}"
