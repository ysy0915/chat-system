#!/bin/bash
# ============================================================
# 一键CI/CD部署脚本（本地化Harness CI/CD等效方案）
# 用法：bash deploy.sh [all|frontend|core|web|games|media]
# 默认：all（全量部署）
# ============================================================
set -e

PROJECT_ROOT="/Users/apple/IdeaProjects/chat-system-project"
FRONTEND_DIR="$PROJECT_ROOT/frontend"
MAIN_PEM="/Users/apple/Downloads/我的密钥.pem"
MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MAIN_SERVER="root@112.124.106.108"
MILVUS_SERVER="root@121.40.188.98"
NGINX_PATH="/opt/app/static/chat"
APP_PATH="/opt/app"

# 颜色输出
red()    { echo -e "\033[31m[FAIL] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK ] $1\033[0m"; }
yellow() { echo -e "\033[33m[INFO] $1\033[0m"; }

TARGET=${1:-all}
START_TIME=$(date +%s)

echo "============================================================"
echo "  AI聊天系统部署  target=$TARGET  $(date)"
echo "============================================================"

# ---------- 健康检查函数 ----------
check_health() {
    local port=$1 name=$2 retries=30
    for i in $(seq 1 $retries); do
        code=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null" || echo "000")
        if [ "$code" = "200" ]; then
            green "$name (port $port) 启动成功"
            return 0
        fi
        sleep 2
    done
    red "$name (port $port) 启动失败 (30秒未健康)"
    return 1
}

# ---------- 前端构建+部署 ----------
deploy_frontend() {
    yellow "[1] 构建前端..."
    cd "$FRONTEND_DIR" && npm run build 2>&1 | tail -3
    if [ ! -d "$FRONTEND_DIR/dist" ]; then red "前端构建失败"; exit 1; fi
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
    local mod=$1 jar=$2 port=$3 name=$4
    yellow "[$mod] 上传jar..."
    scp -i "$MILVUS_PEM" "$PROJECT_ROOT/$mod/target/$jar" $MILVUS_SERVER:$APP_PATH/$mod/
    yellow "[$mod] 重启服务..."
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "bash $APP_PATH/restart-$(echo $mod | sed 's/chat-//').sh"
    check_health $port $name
}

# ---------- 执行部署 ----------
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then
    deploy_frontend
fi

if [ "$TARGET" = "all" ] || [ "$TARGET" = "core" ] || [ "$TARGET" = "web" ] || [ "$TARGET" = "games" ] || [ "$TARGET" = "media" ]; then
    build_backend
fi

case $TARGET in
    all)
        deploy_module chat-core  chat-core-0.0.1-SNAPSHOT.jar  9090 "core"
        deploy_module chat-web   chat-web-0.0.1-SNAPSHOT.jar   8081 "web"
        deploy_module chat-games chat-games-0.0.1-SNAPSHOT.jar 8083 "games"
        deploy_module chat-media chat-media-0.0.1-SNAPSHOT.jar 8084 "media"
        ;;
    core)  deploy_module chat-core  chat-core-0.0.1-SNAPSHOT.jar  9090 "core" ;;
    web)   deploy_module chat-web   chat-web-0.0.1-SNAPSHOT.jar   8081 "web" ;;
    games) deploy_module chat-games chat-games-0.0.1-SNAPSHOT.jar 8083 "games" ;;
    media) deploy_module chat-media chat-media-0.0.1-SNAPSHOT.jar 8084 "media" ;;
esac

# ---------- 部署后验证 ----------
yellow "[验证] 部署后健康检查..."
ALL_OK=true
for port_name in "9090:core" "8081:web" "8083:games" "8084:media"; do
    port=$(echo $port_name | cut -d: -f1)
    name=$(echo $port_name | cut -d: -f2)
    code=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null" || echo "000")
    if [ "$code" = "200" ]; then green "$name: OK"
    else red "$name: FAIL($code)"; ALL_OK=false; fi
done

# 前端验证
fe_code=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/chat/" || echo "000")
if [ "$fe_code" = "200" ]; then green "frontend: OK"
else red "frontend: FAIL($fe_code)"; ALL_OK=false; fi

ELAPSED=$(($(date +%s) - START_TIME))
echo "============================================================"
if [ "$ALL_OK" = "true" ]; then
    green "部署成功！耗时 ${ELAPSED}s"
else
    red "部署完成但有异常，请检查！耗时 ${ELAPSED}s"
    exit 1
fi
echo "============================================================"
