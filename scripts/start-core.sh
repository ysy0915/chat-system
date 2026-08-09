#!/bin/bash
# chat-core 启动脚本（Milvus 服务器，端口 9090，Nacos 注册）
APP_JAR=/opt/app/core/chat-core-0.0.1-SNAPSHOT.jar
LOG_FILE=/opt/app/logs/core-9090.log
PID_FILE=/opt/app/logs/core-9090.pid
HEAP_DUMP=/opt/app/logs/core-heap-dump

if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        kill "$OLD_PID"
        sleep 3
        kill -9 "$OLD_PID" 2>/dev/null
    fi
fi

# JVM 参数：
# -Xmx768m 堆内存上限 768M（之前 512M 太小，LLM 流式调用时会 OOM）
# -XX:+HeapDumpOnOutOfMemoryError OOM 时自动 dump
# -XX:HeapDumpPath dump 文件路径
# -XX:+ExitOnOutOfMemoryError OOM 时直接退出（不卡住）
# -Xss512k 线程栈 512K（减少线程内存占用）
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
echo "core started PID=$(cat $PID_FILE)"
