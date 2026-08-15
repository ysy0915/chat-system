#!/bin/bash
# =============================================================================
# quickstart.sh — 本地一键启动（开源体验优化）
#
# 让评审者/贡献者 clone 后一条命令跑起来：
#   bash scripts/quickstart.sh          # 启动全部中间件 + 编译 + 启动后端 + 前端
#   bash scripts/quickstart.sh infra    # 只启动中间件（MySQL/Redis/RabbitMQ/Nacos/Milvus/Neo4j）
#   bash scripts/quickstart.sh stop     # 停止全部容器
#
# 前置：Docker + JDK 17 + Maven + Node 18+
# =============================================================================

set -euo pipefail
MODE="${1:-all}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

log() { echo -e "\033[1;32m[quickstart]\033[0m $*"; }
warn() { echo -e "\033[1;33m[quickstart][警告]\033[0m $*"; }

start_infra() {
  log "启动中间件（MySQL/Redis/RabbitMQ/Nacos/Milvus/Neo4j）..."
  docker compose --profile dev up -d
  log "等待中间件就绪..."
  # 简单等待容器健康
  sleep 5
  docker compose --profile dev ps
}

start_backend() {
  log "编译后端（mvn clean install）..."
  mvn clean install -DskipTests -q
  log "启动后端服务（后台）..."
  # 使用本地 profile，连接 docker 中间件
  bash scripts/start-background.sh || warn "start-background.sh 执行异常，请手动启动各模块"
}

start_frontend() {
  log "安装前端依赖并启动开发服务器..."
  cd frontend
  [ -d node_modules ] || npm install
  npm run dev &
  log "前端开发服务器启动中：http://localhost:5173"
}

stop_all() {
  log "停止全部容器..."
  docker compose --profile dev --profile all down 2>/dev/null || true
  log "已停止"
}

case "$MODE" in
  infra)  start_infra ;;
  stop)   stop_all ;;
  all)
    start_infra
    start_backend
    start_frontend
    log "=============================================="
    log "启动完成！访问地址："
    log "  前端：http://localhost:5173"
    log "  API ：http://localhost:8080/api/v1/*"
    log "  Swagger：http://localhost:8080/swagger-ui.html"
    log "=============================================="
    ;;
  *)
    warn "未知参数：$MODE（可选：all / infra / stop）"
    exit 1
    ;;
esac
