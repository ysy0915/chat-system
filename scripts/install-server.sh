#!/bin/bash
# ============================================================
# 服务器端一键安装脚本（幂等，可重复执行）
# 覆盖：基础环境(JDK17/Docker) + 中间件(Nacos/Neo4j/Milvus)
#       + Prometheus监控栈 + 重启脚本 + .env 配置
#
# 用法（在服务器上直接执行）：
#   bash /opt/app/install-server.sh            # Milvus 服务器（Java服务+中间件+监控栈）
#   bash /opt/app/install-server.sh --main     # 主服务器（Nginx+Redis+RabbitMQ+前端目录）
#
# 用法（开发机一键，由 deploy.sh 调用）：
#   bash scripts/deploy.sh install
#
# 注意：脚本幂等——已运行的组件自动跳过，.env/restart脚本已存在则不覆盖。
# ============================================================
set -euo pipefail

MODE="${1:-milvus}"
ENV_FILE="/opt/app/.env"
APP="/opt/app"
INTERNAL_IP="172.23.172.13"   # Milvus 服务器内网 IP（Nginx upstream 用）

# ---------------- 输出函数 ----------------
log()  { echo -e "\033[32m[INSTALL] $1\033[0m"; }
warn() { echo -e "\033[33m[ SKIP  ] $1\033[0m"; }
info() { echo -e "\033[36m[ INFO  ] $1\033[0m"; }
die()  { echo -e "\033[31m[ FAIL  ] $1\033[0m"; exit 1; }

# ---------------- 包管理器自适应 ----------------
detect_pkg() {
    if command -v yum >/dev/null 2>&1; then echo "yum"
    elif command -v dnf >/dev/null 2>&1; then echo "dnf"
    elif command -v apt-get >/dev/null 2>&1; then echo "apt"
    else die "不支持的包管理器（仅 yum/dnf/apt）"; fi
}
PKG=$(detect_pkg)
install_pkg() {
    case "$PKG" in
        yum)  yum install -y -q "$@" >/dev/null || return 1 ;;
        dnf)  dnf install -y -q "$@" >/dev/null || return 1 ;;
        apt)  apt-get install -y -qq "$@" >/dev/null || return 1 ;;
    esac
}

# ---------------- 1. 基础环境 ----------------
install_base() {
    log "检查基础环境 (JDK17 / Docker / 工具链)"
    if ! command -v java >/dev/null 2>&1; then
        log "安装 OpenJDK 17"
        install_pkg java-17-openjdk-devel || install_pkg openjdk-17-jdk-headless
    fi
    java -version 2>&1 | head -1

    if ! command -v docker >/dev/null 2>&1; then
        log "安装 Docker"
        if [ "$PKG" = "yum" ] || [ "$PKG" = "dnf" ]; then
            yum install -y -q yum-utils >/dev/null 2>&1 || true
            yum-config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo >/dev/null 2>&1 || true
        fi
        install_pkg docker-ce docker-ce-cli containerd.io || install_pkg docker.io
        systemctl enable --now docker >/dev/null 2>&1 || service docker start >/dev/null 2>&1 || true
    fi
    docker --version

    command -v curl >/dev/null 2>&1  || install_pkg curl
    command -v unzip >/dev/null 2>&1 || install_pkg unzip
    command -v python3 >/dev/null 2>&1 || install_pkg python3
    command -v openssl >/dev/null 2>&1 || install_pkg openssl
    command -v jq >/dev/null 2>&1     || install_pkg jq
}

# ---------------- 2. 目录结构 ----------------
ensure_dirs() {
    log "创建 /opt/app 目录结构"
    mkdir -p $APP/{core,web,games,media,llm,logs,prometheus,prometheus-data,monitoring}
    chown 65534:65534 $APP/prometheus-data 2>/dev/null || true
    # 日志轮转
    cat > /etc/logrotate.d/chat-app <<'EOF'
/opt/app/logs/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    maxsize 200M
}
EOF
}

# ---------------- 3. .env 配置模板（已存在则跳过） ----------------
ensure_env() {
    if [ -f "$ENV_FILE" ]; then
        warn ".env 已存在，跳过生成（如需更新请手动编辑 $ENV_FILE）"
        return
    fi
    log "生成 .env 模板"
    JWT=$(openssl rand -base64 48)
    NEO4J_PWD=$(openssl rand -base64 16 | tr -d '=/+')
    cat > "$ENV_FILE" <<EOF
# ===== AI 聊天系统环境变量（首次安装自动生成，请按需修改） =====

# MySQL（阿里云 RDS）
DB_USERNAME=changeme
DB_PASSWORD=changeme
DB_URL=jdbc:mysql://rm-bp19c29bo9s7kfyb2.mysql.rds.aliyuncs.com:3306/test_data?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai

# Redis
REDIS_HOST=127.0.0.1
REDIS_PORT=6379

# RabbitMQ
RABBITMQ_HOST=127.0.0.1
RABBITMQ_PORT=5672

# JWT（≥32字节，勿泄露）
JWT_SECRET=${JWT}

# Nacos
NACOS_HOST=127.0.0.1
NACOS_PORT=8848
NACOS_USERNAME=nacos
NACOS_PASSWORD=nacos

# Neo4j 知识图谱（chat-llm 使用）
NEO4J_URI=bolt://127.0.0.1:7687
NEO4J_PASSWORD=${NEO4J_PWD}
KNOWLEDGE_GRAPH_ENABLED=true

# Milvus 向量库
MILVUS_ENABLED=true
MILVUS_HOST=127.0.0.1
MILVUS_PORT=19530

# RAG 开关（chat-llm 的 /internal/rag/* 条件注册）
RAG_ENABLED=true
# chat-llm 服务地址（RagClient 与知识库代理指向）
LLM_SERVICE_BASE_URL=http://127.0.0.1:9095

# Embedding（legacy 知识库 1024 维，勿改）
EMBEDDING_MODE=legacy
EMBEDDING_DIMENSION=1024

# ===== LLM API Keys（请填写真实 Key） =====
QIANWEN_API_KEY=changeme
DEEPSEEK_API_KEY=changeme
DOUBAO_API_KEY=changeme

# 钉钉告警 webhook（可选，空则告警仅落盘）
DINGTALK_WEBHOOK=

# ===== 意图识别 =====
INTENT_ENABLED=true
INTENT_MODEL=qwen-turbo
EOF
    chmod 600 "$ENV_FILE"
    info ".env 已生成：$ENV_FILE（请编辑 DB/Redis/LLM Key 等真实凭据）"
}

# ---------------- 4. 中间件（检测已运行则跳过） ----------------
container_running() { docker ps --format '{{.Names}}' | grep -qx "$1"; }

install_middleware() {
    log "检查中间件容器（Nacos / Neo4j / Milvus）"

    if container_running nacos; then
        warn "Nacos 已在运行，跳过"
    else
        log "安装 Nacos 2.2.3（单机模式，内嵌 Derby）"
        docker run -d --name nacos --restart always \
            -p 8848:8848 -p 9848:9848 \
            -e MODE=standalone \
            -e NACOS_AUTH_ENABLE=false \
            nacos/nacos-server:v2.2.3 >/dev/null
    fi

    if container_running neo4j; then
        warn "Neo4j 已在运行，跳过"
    else
        NEO4J_PWD=$(grep '^NEO4J_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
        log "安装 Neo4j 5.x（bolt:7687 / http:7474）"
        docker run -d --name neo4j --restart always \
            -p 7687:7687 -p 7474:7474 \
            -e NEO4J_AUTH="neo4j/${NEO4J_PWD}" \
            -v /opt/app/neo4j-data:/data \
            neo4j:5.20.0 >/dev/null
    fi

    if container_running milvus-standalone; then
        warn "Milvus standalone 已在运行，跳过"
    else
        log "安装 Milvus standalone（etcd + minio + standalone）"
        mkdir -p /opt/app/milvus
        cat > /opt/app/milvus/docker-compose.yml <<'YML'
version: '3.5'
services:
  etcd:
    container_name: milvus-etcd
    image: quay.io/coreos/etcd:v3.5.5
    environment:
      - ETCD_AUTO_COMPACTION_MODE=revision
      - ETCD_AUTO_COMPACTION_RETENTION=1000
      - ETCD_QUOTA_BACKEND_BYTES=4294967296
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/etcd:/etcd
    command: etcd -advertise-client-urls=http://etcd:2379 -listen-client-urls http://0.0.0.0:2379 --data-dir /etcd

  minio:
    container_name: milvus-minio
    image: minio/minio:RELEASE.2023-03-20T20-16-18Z
    environment:
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/minio:/minio_data
    command: minio server /minio_data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 30s
      timeout: 20s
      retries: 3

  standalone:
    container_name: milvus-standalone
    image: milvusdb/milvus:v2.3.4
    command: ["milvus", "run", "standalone"]
    environment:
      ETCD_ENDPOINTS: etcd:2379
      MINIO_ADDRESS: minio:9000
    volumes:
      - ${DOCKER_VOLUME_DIRECTORY:-.}/volumes/milvus:/var/lib/milvus
    ports:
      - "19530:19530"
      - "9091:9091"
    depends_on:
      - etcd
      - minio

networks:
  default:
    name: milvus
YML
        cd /opt/app/milvus && docker compose up -d 2>/dev/null || docker-compose up -d
        warn "Milvus 已启动，注意：9091 为其 metrics 端口，core 从实例请用 9092"
    fi
}

# ---------------- 5. Prometheus 监控栈 ----------------
install_monitoring() {
    log "检查 Prometheus 监控栈"
    if container_running chat-prometheus; then
        warn "Prometheus 栈已存在，跳过（如需重装：docker rm -f chat-prometheus chat-alertmanager chat-node-exporter chat-blackbox）"
        return
    fi

    log "启动 Prometheus 监控栈（host 网络容器）"
    [ -f $APP/prometheus/prometheus.yml ] || die "缺少 $APP/prometheus/prometheus.yml（由 deploy.sh install 上传）"
    [ -f $APP/prometheus/alerts.yml ]      || cp $APP/prometheus/prometheus-alert-rules.yml $APP/prometheus/alerts.yml 2>/dev/null || true

    docker run -d --name chat-prometheus --network host --restart always \
        -v $APP/prometheus-data:/prometheus \
        -v $APP/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro \
        -v $APP/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro \
        prom/prometheus:v2.47.2 --web.listen-address=:9094 --config.file=/etc/prometheus/prometheus.yml >/dev/null

    docker run -d --name chat-alertmanager --network host --restart always \
        -v $APP/prometheus/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro \
        prom/alertmanager:v0.27.0 --cluster.listen-address= --config.file=/etc/alertmanager/alertmanager.yml >/dev/null

    docker run -d --name chat-node-exporter --network host --restart always \
        prom/node-exporter:v1.7.0 >/dev/null

    docker run -d --name chat-blackbox --network host --restart always \
        prom/blackbox-exporter:v0.25.0 >/dev/null

    log "启动告警 webhook（:9950，health-check 每分钟守护）"
    if [ -f $APP/prometheus/alert-webhook.py ]; then
        setsid nohup python3 $APP/prometheus/alert-webhook.py \
            >> /opt/app/logs/prometheus-alerts.log 2>&1 &
    fi

    log "安装 health-check.sh（服务健康检查 + webhook 守护）"
    cat > $APP/health-check.sh <<'EOF'
#!/bin/bash
# 每分钟守护：alert-webhook 进程存活 + 各服务健康检查
WEBHOOK_PY=/opt/app/prometheus/alert-webhook.py
if [ -f "$WEBHOOK_PY" ]; then
    pgrep -f alert-webhook.py >/dev/null || {
        setsid nohup python3 "$WEBHOOK_PY" >> /opt/app/logs/prometheus-alerts.log 2>&1 &
        echo "$(date) [health-check] alert-webhook 已拉起" >> /opt/app/logs/health-check.log
    }
fi
for hp in "9090:core" "9092:core-slave" "8081:web" "8082:web" "8083:games" "8084:media" "9095:llm"; do
    port=${hp%%:*}; name=${hp##*:}
    code=$(curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null)
    if [ "$code" != "200" ]; then
        echo "$(date) [health-check] $name:$port DOWN($code)" >> /opt/app/logs/health-check.log
    fi
done
EOF
    chmod +x $APP/health-check.sh
    # 每分钟 cron 守护
    (crontab -l 2>/dev/null | grep -v health-check.sh || true; echo "* * * * * /opt/app/health-check.sh >/dev/null 2>&1") | crontab -
}

# ---------------- 6. 应用重启脚本（缺失才安装） ----------------
ensure_app_scripts() {
    log "检查应用启动/重启脚本"
    local script
    for script in start-core.sh start-web.sh start-games.sh start-media.sh start-llm.sh \
                  restart-core.sh restart-web.sh restart-games.sh restart-media.sh restart-llm.sh restart-all.sh; do
        [ -f $APP/$script ] || {
            warn "缺少 $APP/$script —— 服务器上已有手工优化版本则忽略；如缺失请从开发机 scripts/ 上传后 chmod +x"
        }
    done
}

# ---------------- 7. 主服务器模式（Nginx + Redis + RabbitMQ） ----------------
install_main_server() {
    log "安装主服务器组件（Nginx / Redis / RabbitMQ）"

    if container_running redis; then
        warn "Redis 已在运行，跳过"
    else
        log "安装 Redis 7"
        docker run -d --name redis --restart always -p 6379:6379 redis:7 >/dev/null
    fi

    if container_running rabbitmq; then
        warn "RabbitMQ 已在运行，跳过"
    else
        log "安装 RabbitMQ 3-management（5672 + 15672 管理台）"
        docker run -d --name rabbitmq --restart always \
            -p 5672:5672 -p 15672:15672 rabbitmq:3-management >/dev/null
        sleep 5
        docker exec rabbitmq rabbitmq-plugins enable rabbitmq_management >/dev/null 2>&1 || true
    fi

    if command -v nginx >/dev/null 2>&1; then
        warn "Nginx 已安装，跳过"
    else
        log "安装 Nginx"
        install_pkg nginx
        systemctl enable --now nginx >/dev/null 2>&1 || service nginx start >/dev/null 2>&1 || true
    fi

    log "创建前端静态目录"
    mkdir -p /opt/app/static/chat

    log "写入前端反代配置 /etc/nginx/conf.d/chat.conf"
    cat > /etc/nginx/conf.d/chat.conf <<EOF
server {
    listen       80 default_server;
    listen       [::]:80 default_server;
    server_name  _;

    client_max_body_size 50m;
    server_tokens off;

    root /opt/app/static/chat;
    index index.html;

    # 静态资源缓存
    location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff2?|ttf|eot)\$ {
        expires 30d;
        add_header Cache-Control "public, immutable";
        try_files \$uri =404;
    }

    location / {
        try_files \$uri \$uri/ /index.html;
    }

    # API 与 WebSocket 反代到 Milvus 服务器（内网）
    location /api/ {
        proxy_pass http://${INTERNAL_IP}:8081;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_http_version 1.1;
        proxy_connect_timeout 5s;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    location /ws/ {
        proxy_pass http://${INTERNAL_IP}:8081;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
EOF
    nginx -t >/dev/null 2>&1 && { systemctl reload nginx >/dev/null 2>&1 || nginx -s reload >/dev/null 2>&1; } || warn "Nginx 配置校验失败，请检查 /etc/nginx/conf.d/chat.conf"
    info "主服务器安装完成：前端 dist 上传到 /opt/app/static/chat/"
}

# ---------------- 主流程 ----------------
main() {
    log "===== 开始安装（模式: $MODE）====="
    [ "$(id -u)" = "0" ] || die "请以 root 运行（sudo bash $0 ...）"

    install_base
    ensure_dirs

    if [ "$MODE" = "--main" ]; then
        install_main_server
    else
        ensure_env
        install_middleware
        install_monitoring
        ensure_app_scripts
    fi

    log "===== 安装完成 ====="
    if [ "$MODE" = "--main" ]; then
        info "下一步：开发机执行  scp -r -i 密钥 frontend/dist/* root@112.124.106.108:/opt/app/static/chat/"
    else
        info "下一步：编辑 /opt/app/.env 填写真实凭据，然后开发机执行 bash scripts/deploy.sh all"
        info "监控台：http://121.40.188.98:9094  |  Nacos: http://121.40.188.98:8848/nacos/ (nacos/nacos)"
    fi
}

main "$@"
