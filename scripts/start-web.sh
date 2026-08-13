#!/bin/bash
# chat-web 启动脚本（Milvus 服务器，端口 8081/8082，Nacos 注册）
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a
APP_JAR=/opt/app/web/chat-web-0.0.1-SNAPSHOT.jar
PORT=${1:-8081}
LOG_FILE=/opt/app/logs/web-${PORT}.log
PID_FILE=/opt/app/logs/web-${PORT}.pid

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi

nohup java \
    -Xms128m -Xmx256m \
    -Xss512k \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/opt/app/logs/web-${PORT}-heap-dump \
    -XX:+ExitOnOutOfMemoryError \
    -jar "$APP_JAR" \
    --spring.profiles.active=prod \
    --server.port=${PORT} \
    --spring.application.name=chat-web \
    --spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 \
    --spring.cloud.nacos.discovery.ip=your-intra-ip \
    --spring.cloud.nacos.discovery.enabled=true \
    --app.core.base-url=http://127.0.0.1:9090 \
    > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "web started PID=$(cat $PID_FILE) PORT=${PORT}"
