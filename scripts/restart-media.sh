#!/bin/bash
# chat-media 重启脚本（端口 8084）
# 用法：bash /opt/app/restart-media.sh

PORT=8084
APP_JAR=/opt/app/media/chat-media-0.0.1-SNAPSHOT.jar
LOG_FILE=/opt/app/logs/media-8084.log
PID_FILE=/opt/app/logs/media-8084.pid

echo "[media] 重启开始 $(date)"

# 杀旧进程
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[media] 杀死旧进程 PID=$OLD_PID"
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi

# 等端口释放
for i in $(seq 1 10); do
    if ! ss -tlnp | grep -q ":$PORT "; then
        echo "[media] 端口 $PORT 已释放"
        break
    fi
    echo "[media] 端口 $PORT 仍被占用，等待... ($i/10)"
    sleep 2
done

# 启动
nohup java \
    -Xms128m -Xmx256m \
    -Xss512k \
    -XX:+UseG1GC \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/opt/app/logs/media-heap-dump \
    -XX:+ExitOnOutOfMemoryError \
    -jar "$APP_JAR" \
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
    --app.langchain4j.enabled=false \
    --app.langgraph4j.enabled=false \
    --app.rag.enabled=false \
    --app.rag.milvus.enabled=false \
    > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "[media] 启动中 PID=$(cat $PID_FILE)"

# 等待健康检查
for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$PORT/actuator/health 2>/dev/null | grep -q '200'; then
        echo "[media] 启动成功 (第 ${i} 次检测) $(date)"
        exit 0
    fi
    sleep 3
done
echo "[media] 启动超时！请检查日志: $LOG_FILE"
exit 1
