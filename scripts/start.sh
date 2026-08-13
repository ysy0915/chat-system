#!/bin/bash

# ─── 配置 ────────────────────────────────────────────────────────────────────
JAR_SRC="/opt/chat-system-project-0.0.1-SNAPSHOT.jar"
APP_DIR="/opt/app"
JAR_NAME="chat-system-project-0.0.1-SNAPSHOT.jar"
JAR_PATH="$APP_DIR/$JAR_NAME"
LOG_DIR="$APP_DIR/logs"
PORTS=(8081 8082)       # 需要管理的端口列表
STARTUP_TIMEOUT=90      # 最长等待启动秒数
HEALTH_CHECK_INTERVAL=3 # 每隔几秒检测一次

# ─── 工具函数 ─────────────────────────────────────────────────────────────────
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# 获取占用指定端口的 PID（兼容 lsof / ss / fuser）
get_port_pid() {
    local port=$1
    # 优先 lsof
    if command -v lsof >/dev/null 2>&1; then
        lsof -ti tcp:"$port" 2>/dev/null | head -1
        return
    fi
    # 降级 ss
    if command -v ss >/dev/null 2>&1; then
        ss -ltnp 2>/dev/null | awk -v p=":$port" '$4 ~ p {match($0,/pid=([0-9]+)/,a); print a[1]; exit}'
        return
    fi
    # 降级 fuser
    if command -v fuser >/dev/null 2>&1; then
        fuser "$port"/tcp 2>/dev/null | tr -d ' '
        return
    fi
    echo ""
}

# 杀死占用指定端口的进程
kill_port() {
    local port=$1
    local pid
    pid=$(get_port_pid "$port")
    if [ -n "$pid" ]; then
        log "杀死端口 $port 的进程 PID=$pid"
        kill -15 "$pid" 2>/dev/null
        # 优雅等待最多 5 秒
        local waited=0
        while kill -0 "$pid" 2>/dev/null && [ $waited -lt 5 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        # 若仍未退出则强杀
        if kill -0 "$pid" 2>/dev/null; then
            log "进程未退出，强制 kill -9 PID=$pid"
            kill -9 "$pid" 2>/dev/null
            sleep 1
        fi
        log "端口 $port 已释放"
    else
        log "端口 $port 无运行进程，跳过"
    fi
}

# 检查端口是否在监听（仅判断 TCP 连通，避免 HTTP 404 误判）
is_port_listening() {
    local port=$1
    # 优先用 bash 内置 /dev/tcp（无依赖）
    (echo >"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
}

# 等待应用在指定端口完全启动
wait_for_port() {
    local port=$1
    local elapsed=0
    log "等待应用在端口 $port 启动..."
    while [ $elapsed -lt $STARTUP_TIMEOUT ]; do
        if is_port_listening "$port"; then
            # 二次确认：actuator/health 返回 200 表示真正 ready
            if curl -sf --max-time 3 "http://localhost:$port/actuator/health" >/dev/null 2>&1; then
                log "应用已在端口 $port 启动完成（actuator health UP，耗时 ${elapsed}s）"
                return 0
            fi
            # actuator 没起来但端口已通，也算启动（部分接口未暴露 actuator）
            if [ $elapsed -ge 6 ]; then
                log "应用已在端口 $port 启动完成（端口监听，耗时 ${elapsed}s）"
                return 0
            fi
        fi
        sleep $HEALTH_CHECK_INTERVAL
        elapsed=$((elapsed + HEALTH_CHECK_INTERVAL))
    done
    log "错误：等待端口 $port 超时（${STARTUP_TIMEOUT}s），请检查日志：$LOG_DIR/app-$port.log"
    exit 1
}

# 启动 Spring Boot
start_app() {
    local port=$1
    local log_file="$LOG_DIR/app-$port.log"
    log "启动应用，端口=$port，日志=$log_file"
    nohup java -jar "$JAR_PATH" \
        --server.port="$port" \
        > "$log_file" 2>&1 &
    log "已后台启动，PID=$!"
}

# ─── 主流程 ───────────────────────────────────────────────────────────────────

# 1. 校验并移动 jar 包
log "===== 步骤 1：移动 jar 包 ====="
if [ ! -f "$JAR_SRC" ]; then
    log "错误：源文件不存在 $JAR_SRC"
    exit 1
fi
mkdir -p "$APP_DIR" "$LOG_DIR"
cp -f "$JAR_SRC" "$JAR_PATH"
log "已复制：$JAR_SRC → $JAR_PATH"

# 2. 依次对每个端口执行：kill → 启动 → 等待就绪
log ""
log "===== 步骤 2：滚动重启（kill → 启动 → 等待）====="
for port in "${PORTS[@]}"; do
    log ""
    log "----- 重启端口 $port -----"
    kill_port "$port"
    start_app "$port"
    wait_for_port "$port"
done

log ""
log "===== 部署完成 ✓ 端口 ${PORTS[*]} 均已启动 ====="
