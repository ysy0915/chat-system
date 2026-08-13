#!/bin/bash
# ============================================================
# 混沌工程测试脚本（本地化Harness Resilience Testing等效方案）
# 模拟各种故障场景，验证系统容灾能力
# 用法：bash chaos-test.sh [core-kill|redis-down|rabbit-down|mysql-slow|llm-timeout|all]
# ============================================================

MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MAIN_PEM="/Users/apple/Downloads/我的密钥.pem"
MILVUS_SERVER="root@your-milvus-ip"
MAIN_SERVER="root@your-nginx-ip"

red()    { echo -e "\033[31m[CHAOS] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK ]  $1\033[0m"; }
yellow() { echo -e "\033[33m[TEST]  $1\033[0m"; }
blue()   { echo -e "\033[34m[INFO]  $1\033[0m"; }

SCENARIO=${1:-all}

echo "============================================================"
echo "  混沌工程测试  scenario=$SCENARIO  $(date)
============================================================"

# ---------- 测试1：杀掉core进程 ----------
test_core_kill() {
    blue "========== 场景1: 杀掉core进程 =========="
    yellow "模拟core宕机..."

    # 记录杀之前的状态
    BEFORE=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health")
    echo "  core杀之前: HTTP $BEFORE"

    # 杀掉core
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "pkill -f 'java.*chat-core' 2>/dev/null"
    sleep 3

    # 验证core确实挂了
    AFTER=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health 2>/dev/null || echo '000'")
    if [ "$AFTER" = "000" ]; then
        green "  core已停止: HTTP $AFTER"
    else
        red "  core还在运行: HTTP $AFTER"
    fi

    # 验证web是否还能响应（应该返回错误但进程不死）
    WEB=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health")
    echo "  web在core挂了之后: HTTP $WEB"
    if [ "$WEB" = "200" ]; then
        green "  ✓ web进程存活（核心进程隔离成功）"
    else
        red "  ✗ web也挂了（进程隔离失败）"
    fi

    # 恢复
    yellow "  恢复core..."
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "bash /opt/app/restart-core.sh"
    sleep 5

    # 验证恢复
    RECOVERED=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health")
    if [ "$RECOVERED" = "200" ]; then green "  ✓ core已恢复"
    else red "  ✗ core恢复失败"; fi
}

# ---------- 测试2：Redis断开 ----------
test_redis_down() {
    blue "========== 场景2: Redis断开 =========="
    yellow "模拟Redis不可用..."

    # 关闭Redis
    ssh -i "$MAIN_PEM" $MAIN_SERVER "/usr/local/redis/bin/redis-cli shutdown nosave 2>/dev/null"
    sleep 3

    # 验证web是否报错（应该能启动但缓存功能失效）
    WEB=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health")
    echo "  web在Redis断开后: HTTP $WEB"

    # 检查core是否报错
    CORE_ERR=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "tail -5 /opt/app/logs/chat-core.log 2>/dev/null | grep -i 'redis\|connection' | head -1")
    if [ -n "$CORE_ERR" ]; then
        yellow "  core日志: $CORE_ERR"
    fi

    # 恢复
    yellow "  恢复Redis..."
    ssh -i "$MAIN_PEM" $MAIN_SERVER "/usr/local/redis/bin/redis-server /usr/local/redis/conf/redis.conf --daemonize yes" 2>/dev/null
    sleep 3

    REDIS_OK=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "/usr/local/redis/bin/redis-cli ping 2>/dev/null")
    if [ "$REDIS_OK" = "PONG" ]; then green "  ✓ Redis已恢复"
    else red "  ✗ Redis恢复失败"; fi
}

# ---------- 测试3：RabbitMQ断开 ----------
test_rabbit_down() {
    blue "========== 场景3: RabbitMQ断开 =========="
    yellow "模拟RabbitMQ不可用..."

    ssh -i "$MAIN_PEM" $MAIN_SERVER "rabbitmqctl stop_app 2>/dev/null"
    sleep 3

    # 验证发送消息是否失败
    RESULT=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 10 -X POST http://localhost:8081/api/v1/messages -H 'Content-Type: application/json' -d '{\"question\":\"test\",\"user_id\":0,\"req_id\":\"chaos-test\"}'" 2>/dev/null)
    echo "  发送消息结果: $RESULT"

    # 恢复
    yellow "  恢复RabbitMQ..."
    ssh -i "$MAIN_PEM" $MAIN_SERVER "rabbitmqctl start_app 2>/dev/null"
    sleep 3

    RABBIT_OK=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "rabbitmqctl status 2>/dev/null | grep 'running' | head -1")
    if [ -n "$RABBIT_OK" ]; then green "  ✓ RabbitMQ已恢复"
    else red "  ✗ RabbitMQ恢复失败"; fi
}

# ---------- 测试4：LLM超时（网络延迟注入） ----------
test_llm_timeout() {
    blue "========== 场景4: LLM API超时 =========="
    yellow "模拟LLM API响应超时（通过防火墙阻断）..."

    # 获取LLM API的IP
    LLM_IP=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "dig +short dashscope.aliyuncs.com 2>/dev/null | head -1")
    if [ -z "$LLM_IP" ]; then
        yellow "  无法解析LLM API IP，跳过"
        return
    fi
    echo "  LLM API IP: $LLM_IP"

    # 注入10秒网络延迟（用tc）
    yellow "  注入10秒网络延迟..."
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "tc qdisc add dev eth0 root netem delay 10s 2>/dev/null || echo 'tc不可用'"

    # 发送测试请求，看熔断器是否触发
    RESULT=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "curl -s -m 30 -X POST http://localhost:8081/api/v1/messages -H 'Content-Type: application/json' -d '{\"question\":\"test\",\"user_id\":0,\"req_id\":\"chaos-llm\"}'" 2>/dev/null)
    echo "  请求结果: $RESULT"

    # 检查熔断器状态
    CB_STATUS=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "grep 'CircuitBreaker' /opt/app/logs/chat-core.log 2>/dev/null | tail -3")
    if [ -n "$CB_STATUS" ]; then
        green "  熔断器日志: $CB_STATUS"
    else
        yellow "  未检测到熔断器触发"
    fi

    # 恢复
    yellow "  恢复网络..."
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "tc qdisc del dev eth0 root 2>/dev/null"
    green "  ✓ 网络已恢复"
}

# ---------- 执行 ----------
case $SCENARIO in
    core-kill)    test_core_kill ;;
    redis-down)   test_redis_down ;;
    rabbit-down)  test_rabbit_down ;;
    llm-timeout)  test_llm_timeout ;;
    all)
        test_core_kill
        echo ""
        test_redis_down
        echo ""
        test_rabbit_down
        echo ""
        test_llm_timeout
        ;;
    *)
        echo "用法: bash chaos-test.sh [core-kill|redis-down|rabbit-down|llm-timeout|all]"
        exit 1
        ;;
esac

echo ""
echo "============================================================"
echo "  混沌测试完成 $(date '+%H:%M:%S')
============================================================"
