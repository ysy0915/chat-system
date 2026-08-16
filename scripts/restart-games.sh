#!/bin/bash
# chat-games 重启脚本（端口 8083）
# 加载环境变量
[ -f /opt/app/.env ] && set -a && . /opt/app/.env && set +a
# 用法：bash /opt/app/restart-games.sh

PORT=8083
APP_JAR=/opt/app/games/chat-games-0.0.1-SNAPSHOT.jar
LOG_FILE=/opt/app/logs/games-8083.log
PID_FILE=/opt/app/logs/games-8083.pid

echo "[games] 重启开始 $(date)"

# 1. 通过 PID 文件杀进程
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[games] 杀死旧进程 PID=$OLD_PID"
        kill "$OLD_PID" 2>/dev/null
        sleep 2
        kill -9 "$OLD_PID" 2>/dev/null
    fi
    rm -f "$PID_FILE"
fi

# 2. pkill 兜底杀所有 chat-games 进程
pkill -9 -f 'chat-games' 2>/dev/null
sleep 1

# 3. 端口强杀：如果端口仍被占用，找到占用进程并杀掉
for i in $(seq 1 15); do
    PORT_PID=$(ss -tlnp | grep ":$PORT " | grep -oP 'pid=\K\d+' | head -1)
    if [ -z "$PORT_PID" ]; then
        echo "[games] 端口 $PORT 已释放"
        break
    fi
    echo "[games] 端口 $PORT 仍被 PID=$PORT_PID 占用，强制杀死 ($i/15)"
    kill -9 "$PORT_PID" 2>/dev/null
    sleep 2
done

# 启动
nohup java \
    -Xms128m -Xmx128m \
    -Xss256k \
    -XX:MaxMetaspaceSize=128m \
        -XX:MaxDirectMemorySize=64m \
    -XX:ReservedCodeCacheSize=48m \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=200 \
    -XX:G1HeapRegionSize=1m \
    -XX:InitiatingHeapOccupancyPercent=45 \
    -XX:ParallelGCThreads=2 \
    -XX:ConcGCThreads=1 \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/opt/app/logs/games-heap-dump \
    -XX:+ExitOnOutOfMemoryError \
    -XX:+UseContainerSupport \
    -Xlog:gc*:file=/opt/app/logs/gc-games.log:time,uptime,level,tags:filecount=5,filesize=10m \
    -DLOG_PATH=/opt/app/logs \
    -jar "$APP_JAR" \
    --spring.profiles.active=prod \
    --server.port=8083 \
    --server.tomcat.threads.max=50 \
    --server.tomcat.threads.min-spare=4 \
    --spring.application.name=chat-games \
    --app.module.core=false \
    --app.observability.enabled=false \
    --app.router.enabled=false \
    --app.langchain4j.enabled=false \
    --app.langgraph4j.enabled=false \
    --app.rag.enabled=false \
    --app.rag.milvus.enabled=false \
    > "$LOG_FILE" 2>&1 &

echo $! > "$PID_FILE"
echo "[games] 启动中 PID=$(cat $PID_FILE)"

for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$PORT/actuator/health 2>/dev/null | grep -q '200'; then
        echo "[games] 启动成功 (第 ${i} 次检测) $(date)"
        rm -f /opt/app/logs/games-degraded
        exit 0
    fi
    sleep 3
done
echo "[games] 启动超时！请检查日志: $LOG_FILE"
exit 1
