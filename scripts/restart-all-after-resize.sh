#!/bin/bash
# ============================================================
# 阿里云扩容后服务重启脚本
# 使用方式：ssh 到服务器后执行 bash /opt/app/restart-all-after-resize.sh
#
# 执行顺序（按依赖关系）：
#   1. MySQL   （应用依赖数据库）
#   2. Redis   （应用依赖缓存）
#   3. RabbitMQ（应用依赖消息队列，虽当前 auto-startup=false）
#   4. Java 应用（8081 + 8082，通过 restart.sh）
#   5. Nginx   （反向代理，最后启动）
# ============================================================

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }

# ========== 1. MySQL ==========
log "===== 1/5 重启 MySQL ====="
systemctl restart mysqld
sleep 3
if systemctl is-active mysqld --quiet; then
    log "MySQL 启动成功"
else
    log "MySQL 启动失败！请检查：journalctl -u mysqld -n 50"
    exit 1
fi

# 等待 MySQL 完全就绪（能接受连接）
log "等待 MySQL 就绪..."
for i in $(seq 1 30); do
    if mysqladmin ping -h 127.0.0.1 --silent 2>/dev/null; then
        log "MySQL 已就绪"
        break
    fi
    sleep 1
    if [ $i -eq 30 ]; then
        log "MySQL 30 秒内未就绪，继续后续步骤..."
    fi
done

# ========== 2. Redis ==========
log "===== 2/5 重启 Redis ====="
systemctl restart redis
sleep 2
if systemctl is-active redis --quiet; then
    log "Redis 启动成功"
else
    log "Redis 启动失败！请检查：journalctl -u redis -n 50"
    exit 1
fi

# 验证 Redis 能 ping 通
redis-cli ping 2>/dev/null || log "Redis ping 未响应（可能需要密码），继续..."

# ========== 3. RabbitMQ ==========
log "===== 3/5 重启 RabbitMQ ====="
systemctl restart rabbitmq-server
sleep 5
if systemctl is-active rabbitmq-server --quiet; then
    log "RabbitMQ 启动成功"
else
    log "RabbitMQ 启动失败！请检查：journalctl -u rabbitmq-server -n 50"
    # RabbitMQ 当前 auto-startup=false，不阻塞后续启动
    log "RabbitMQ 未就绪，但应用未强依赖，继续..."
fi

# ========== 4. Java 应用（8081 + 8082）==========
log "===== 4/5 重启 Java 应用 ====="
bash /opt/app/restart.sh
if [ $? -ne 0 ]; then
    log "Java 应用重启失败！请检查 /opt/app/logs/app-8081.log 和 app-8082.log"
    exit 1
fi

# ========== 5. Nginx ==========
log "===== 5/5 重启 Nginx ====="
nginx -t 2>&1
if [ $? -eq 0 ]; then
    systemctl restart nginx
    sleep 2
    if systemctl is-active nginx --quiet; then
        log "Nginx 启动成功"
    else
        log "Nginx 启动失败！请检查：nginx -t && journalctl -u nginx -n 50"
        exit 1
    fi
else
    log "Nginx 配置测试失败，尝试 reload..."
    systemctl reload nginx
fi

# ========== 最终检查 ==========
log ""
log "===== 服务状态检查 ====="
echo "MySQL:     $(systemctl is-active mysqld)"
echo "Redis:     $(systemctl is-active redis)"
echo "RabbitMQ:  $(systemctl is-active rabbitmq-server)"
echo "Nginx:     $(systemctl is-active nginx)"
echo "Java 8081: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health)"
echo "Java 8082: $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/actuator/health)"
echo "Nginx 80:  $(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:80)"

log ""
log "===== 全部重启完成 ====="
log "如遇异常，查看日志："
log "  MySQL:    journalctl -u mysqld -n 50"
log "  Redis:    journalctl -u redis -n 50"
log "  RabbitMQ: journalctl -u rabbitmq-server -n 50"
log "  Nginx:    journalctl -u nginx -n 50"
log "  Java:     tail -50 /opt/app/logs/app-8081.log /opt/app/logs/app-8082.log"
