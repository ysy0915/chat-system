#!/bin/bash
# 全量重启脚本：杀所有 Java 进程 → 等端口释放 → 按顺序启动全部服务
# 用法：bash /opt/app/restart-all.sh

LOG_DIR=/opt/app/logs
mkdir -p $LOG_DIR

echo "===== 全量重启开始 $(date) ====="

# 步骤1：杀所有 Java 进程
echo "[1/6] 杀死所有 Java 进程..."
pkill -9 -f java 2>/dev/null
sleep 3

# 步骤2：等待端口完全释放
echo "[2/6] 等待端口释放..."
for port in 9090 8081 8082 8083 8084 9095 9096; do
    for i in $(seq 1 10); do
        if ! ss -tlnp | grep -q ":$port "; then
            echo "  端口 $port 已释放"
            break
        fi
        echo "  端口 $port 仍被占用，等待... ($i/10)"
        sleep 2
    done
done

# 步骤3：启动 chat-core
echo "[3/6] 启动 chat-core (9090)..."
bash /opt/app/start-core.sh
echo "  等待 chat-core 启动..."
for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health 2>/dev/null | grep -q '200'; then
        echo "  chat-core 启动成功 (第 ${i} 次检测)"
        break
    fi
    sleep 3
done

# 步骤4：启动 chat-web 双实例
echo "[4/6] 启动 chat-web (8081/8082)..."
bash /opt/app/start-web.sh 8081
bash /opt/app/start-web.sh 8082
echo "  等待 chat-web 启动..."
for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health 2>/dev/null | grep -q '200'; then
        echo "  chat-web 8081 启动成功 (第 ${i} 次检测)"
        break
    fi
    sleep 3
done

# 步骤5：启动 games + media
echo "[5/6] 启动 games (8083) + media (8084)..."
bash /opt/app/start-games.sh
bash /opt/app/start-media.sh

# 步骤6：启动 chat-llm 双实例（Nacos 注册）
echo "[6/6] 启动 chat-llm (9095/9096)..."
bash /opt/app/start-llm.sh 9095
bash /opt/app/start-llm.sh 9096
echo "  等待 chat-llm 启动..."
for i in $(seq 1 20); do
    if curl -s -m 2 -o /dev/null -w '%{http_code}' http://localhost:9095/actuator/health 2>/dev/null | grep -q '200'; then
        echo "  chat-llm 9095 启动成功 (第 ${i} 次检测)"
        break
    fi
    sleep 3
done

# 启动守护进程（防 chat-core 挂掉）
pkill -f daemon-core.sh 2>/dev/null
setsid bash /opt/app/daemon-core.sh </dev/null >$LOG_DIR/daemon-core.log 2>&1 &

echo ""
echo "===== 重启完成 $(date) ====="
echo "服务状态："
for svc in "core:9090" "web1:8081" "web2:8082" "games:8083" "media:8084" "llm1:9095" "llm2:9096"; do
    name=${svc%%:*}
    port=${svc##*:}
    code=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://localhost:$port/actuator/health 2>/dev/null)
    echo "  $name ($port): $code"
done
