#!/bin/bash
# chat-media 启动脚本（Milvus 服务器，端口 8084，Nacos 注册）
APP_JAR=/opt/app/media/chat-media-0.0.1-SNAPSHOT.jar
LOG_FILE=/opt/app/logs/media-8084.log
PID_FILE=/opt/app/logs/media-8084.pid

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi

nohup java -jar "$APP_JAR" \
    --spring.profiles.active=prod \
    --server.port=8084 \
    --spring.application.name=chat-media \
    --spring.cloud.nacos.discovery.server-addr=127.0.0.1:8848 \
    --spring.cloud.nacos.discovery.ip=172.23.172.13 \
    --spring.cloud.nacos.discovery.enabled=true \
    --spring.data.redis.host=172.18.160.222 \
    --spring.data.redis.port=6379 \
    --app.module.core=false \
    --app.observability.enabled=false \
    --app.router.enabled=false \
    --app.langchain4j.enabled=false \
    --app.langgraph4j.enabled=false \
    --app.rag.enabled=false \
    --app.rag.milvus.enabled=false \
    > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "media started PID=$(cat $PID_FILE)"
