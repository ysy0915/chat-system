#!/bin/bash
# web 双实例滚动重启（本机执行）：摘节点 → 优雅停 → 起新 → 加回
# 任意时刻至少一个实例在线，WS 客户端自动重连到存活节点
# 用法：bash scripts/rolling-restart-web.sh [8081|8082|all]
set -u
M_KEY="/Users/apple/Downloads/我的密钥.pem"
L_KEY="/Users/apple/Downloads/Milvus.pem"
MAIN="root@112.124.106.108"
MILVUS="root@121.40.188.98"
CONF="/etc/nginx/nginx.conf"
TARGET=${1:-all}

drain() {
    local P=$1
    echo "=== [web-$P] 摘除 Nginx 节点(down) ==="
    ssh -i "$M_KEY" "$MAIN" "sed -i 's|server 172.23.172.13:$P max_fails=2 fail_timeout=5s;|server 172.23.172.13:$P max_fails=2 fail_timeout=5s down;|' $CONF && nginx -t && nginx -s reload"
    sleep 5
}
restore() {
    local P=$1
    echo "=== [web-$P] 加回 Nginx 节点 ==="
    ssh -i "$M_KEY" "$MAIN" "sed -i 's|server 172.23.172.13:$P max_fails=2 fail_timeout=5s down;|server 172.23.172.13:$P max_fails=2 fail_timeout=5s;|' $CONF && nginx -t && nginx -s reload"
}
graceful_stop() {
    local P=$1
    echo "=== [web-$P] 优雅停机(TERM) ==="
    ssh -i "$L_KEY" "$MILVUS" "
        PID=\$(cat /opt/app/logs/web-$P.pid 2>/dev/null || echo '')
        if [ -n \"\$PID\" ] && kill -0 \$PID 2>/dev/null; then
            kill -TERM \$PID
            for i in \$(seq 1 15); do
                ss -tlnp | grep -q ':$P ' || { echo '  端口 $P 已释放'; exit 0; }
                sleep 2
            done
            echo '  优雅超时, kill -9 兜底'
            kill -9 \$PID 2>/dev/null
            for i in \$(seq 1 10); do ss -tlnp | grep -q ':$P ' || break; sleep 2; done
        else
            echo '  无 PID 或进程已死'
        fi
    "
}
roll_one() {
    local P=$1
    drain $P
    graceful_stop $P
    echo "=== [web-$P] 启动新实例 ==="
    ssh -i "$L_KEY" "$MILVUS" "bash /opt/app/restart-web.sh $P" || { restore $P; echo "[web-$P] 失败, 已加回节点"; exit 1; }
    restore $P
    echo "=== [web-$P] 完成 ==="
}

if [ "$TARGET" = "all" ]; then roll_one 8081; roll_one 8082; else roll_one "$TARGET"; fi
echo "===== 滚动重启完成 $(date) ====="
