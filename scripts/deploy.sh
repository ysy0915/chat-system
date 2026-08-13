#!/bin/bash
# ============================================================
# 一键自动化部署脚本（开发机执行）
# 覆盖：安装（服务器环境初始化）+ 构建 + 上传 + 重启 + 健康检查
#
# 用法：
#   bash deploy.sh install                # 首次：安装服务器环境（Milvus服务器）
#   bash deploy.sh install --main         # 首次：安装主服务器（Nginx/Redis/RabbitMQ）
#   bash deploy.sh all                    # 全量：前端+后端构建+上传+重启+验证
#   bash deploy.sh frontend               # 仅前端构建+部署
#   bash deploy.sh core|web|games|media|llm   # 仅单个后端模块
#   bash deploy.sh install-all            # 安装两台服务器 + 全量部署（全新上线）
#
# 依赖：本机已装 JDK17 + Maven + Node18
# ============================================================
set -e

PROJECT_ROOT="/Users/apple/IdeaProjects/chat-system-project"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
MAIN_PEM="/Users/apple/Downloads/我的密钥.pem"
MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MAIN_SERVER="root@your-nginx-ip"
MILVUS_SERVER="root@your-milvus-ip"
NGINX_PATH="/opt/app/static/chat"
APP_PATH="/opt/app"

# 颜色输出
red()    { echo -e "\033[31m[FAIL] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK ] $1\033[0m"; }
yellow() { echo -e "\033[33m[INFO] $1\033[0m"; }

TARGET=${1:-all}
EXTRA=${2:-}
START_TIME=$(date +%s)

echo "============================================================"
echo "  AI聊天系统一键部署  target=$TARGET  $(date)"
echo "============================================================"

# ---------- 健康检查函数（支持多端口） ----------
check_health() {
    # 用法: check_health "9090:core" "9092:core-slave" ...
    local all_ok=true
    for entry in "$@"; do
        local port=$(echo "$entry" | cut -d: -f1)
        local name=$(echo "$entry" | cut -d: -f2)
        local ok=false
        for i in $(seq 1 20); do
            code=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null" || echo "000")
            if [ "$code" = "200" ]; then green "$name (port $port) 健康"; ok=true; break; fi
            sleep 3
        done
        [ "$ok" = "true" ] || { red "$name (port $port) 启动失败/不健康"; all_ok=false; }
    done
    [ "$all_ok" = "true" ]
}

# ---------- 服务器安装 ----------
install_server() {
    local mode="$1" server pem host label
    if [ "$mode" = "--main" ]; then
        server=$MAIN_SERVER; pem=$MAIN_PEM; label="主服务器"
    else
        server=$MILVUS_SERVER; pem=$MILVUS_PEM; label="Milvus 服务器"
    fi

    yellow "[install] 上传安装脚本与监控配置到 $label ($server)..."
    scp -q -i "$pem" "$PROJECT_ROOT/scripts/install-server.sh" $server:$APP_PATH/
    if [ "$mode" != "--main" ]; then
        scp -q -i "$pem" \
            "$PROJECT_ROOT/docs/prometheus-prod.yml"       $server:$APP_PATH/prometheus/prometheus.yml
        scp -q -i "$pem" \
            "$PROJECT_ROOT/docs/prometheus-alert-rules.yml" $server:$APP_PATH/prometheus/
        scp -q -i "$pem" \
            "$PROJECT_ROOT/docs/alertmanager.yml"           $server:$APP_PATH/prometheus/
        scp -q -i "$pem" \
            "$PROJECT_ROOT/docs/alert-webhook.py"           $server:$APP_PATH/prometheus/
    fi

    yellow "[install] 在服务器上执行安装..."
    ssh -i "$pem" $server "chmod +x $APP_PATH/install-server.sh && bash $APP_PATH/install-server.sh $mode"
    green "[install] $label 安装完成"
}

# ---------- 前端构建+部署 ----------
deploy_frontend() {
    yellow "[1] 构建前端..."
    cd "$FRONTEND_DIR" && npm run build 2>&1 | tail -3
    [ -d "$FRONTEND_DIR/dist" ] || { red "前端构建失败"; exit 1; }
    green "前端构建完成"

    yellow "[2] 上传前端到Nginx服务器..."
    scp -r -i "$MAIN_PEM" "$FRONTEND_DIR/dist/"* $MAIN_SERVER:$NGINX_PATH/
    green "前端部署完成"
}

# ---------- 后端打包 ----------
build_backend() {
    yellow "[3] Maven全量打包..."
    cd "$PROJECT_ROOT" && mvn clean install -DskipTests -q 2>&1 | tail -3
    green "Maven打包完成"
}

# ---------- 单模块部署 ----------
deploy_module() {
    local mod=$1 jar=$2 name=$3
    shift 3
    yellow "[$mod] 上传jar..."
    scp -q -i "$MILVUS_PEM" "$PROJECT_ROOT/$mod/target/$jar" $MILVUS_SERVER:$APP_PATH/$mod/
    yellow "[$mod] 重启服务..."
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "bash $APP_PATH/restart-$(echo $mod | sed 's/chat-//').sh"
    check_health "$@"
}

# ---------- 部署后全量验证 ----------
verify_all() {
    yellow "[验证] 部署后健康检查（双 core + 双 web + games + media + llm）..."
    check_health "9090:core主" "9092:core从" "8081:web-1" "8082:web-2" "8083:games" "8084:media" "9095:llm" || return 1

    yellow "[验证] 前端可达性..."
    fe_code=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:80/ 2>/dev/null" || echo "000")
    if [ "$fe_code" = "200" ]; then green "frontend: OK"
    else red "frontend: FAIL($fe_code)"; return 1; fi
    return 0
}

# ---------- 执行 ----------
case "$TARGET" in
    install)
        if [ "$EXTRA" = "--main" ]; then install_server --main
        else install_server ""; fi ;;

    install-main)
        install_server --main ;;

    install-all)
        install_server --main
        install_server ""
        deploy_frontend
        build_backend
        # 启动顺序：llm（RAG/图谱依赖）→ core → web → games/media
        deploy_module chat-llm   chat-llm-0.0.1-SNAPSHOT.jar   "llm"          9095:llm
        deploy_module chat-core  chat-core-0.0.1-SNAPSHOT.jar  "core双实例"  9090:core主 9092:core从
        deploy_module chat-web   chat-web-0.0.1-SNAPSHOT.jar   "web双实例"    8081:web-1 8082:web-2
        deploy_module chat-games chat-games-0.0.1-SNAPSHOT.jar "games"        8083:games
        deploy_module chat-media chat-media-0.0.1-SNAPSHOT.jar "media"        8084:media
        verify_all ;;

    all|frontend|core|web|games|media|llm)
        if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then deploy_frontend; fi
        if [ "$TARGET" = "all" ] || [ "$TARGET" != "frontend" ]; then build_backend; fi
        case "$TARGET" in
            all)   deploy_module chat-core  chat-core-0.0.1-SNAPSHOT.jar  "core双实例"  9090:core主 9092:core从
                   deploy_module chat-llm   chat-llm-0.0.1-SNAPSHOT.jar   "llm"          9095:llm
                   deploy_module chat-web   chat-web-0.0.1-SNAPSHOT.jar   "web双实例"    8081:web-1 8082:web-2
                   deploy_module chat-games chat-games-0.0.1-SNAPSHOT.jar "games"        8083:games
                   deploy_module chat-media chat-media-0.0.1-SNAPSHOT.jar "media"        8084:media ;;
            core)  deploy_module chat-core  chat-core-0.0.1-SNAPSHOT.jar  "core双实例"  9090:core主 9092:core从 ;;
            llm)   deploy_module chat-llm   chat-llm-0.0.1-SNAPSHOT.jar   "llm"          9095:llm ;;
            web)   deploy_module chat-web   chat-web-0.0.1-SNAPSHOT.jar   "web双实例"    8081:web-1 8082:web-2 ;;
            games) deploy_module chat-games chat-games-0.0.1-SNAPSHOT.jar "games"        8083:games ;;
            media) deploy_module chat-media chat-media-0.0.1-SNAPSHOT.jar "media"        8084:media ;;
        esac
        if [ "$TARGET" = "all" ]; then verify_all; fi ;;

    *)
        echo "用法: bash deploy.sh [install|install-main|install-all|all|frontend|core|web|games|media|llm]"
        exit 1 ;;
esac

ELAPSED=$(($(date +%s) - START_TIME))
echo "============================================================"
green "部署完成！耗时 ${ELAPSED}s"
echo "============================================================"
