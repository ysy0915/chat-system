#!/bin/bash
# ============================================================
# 云成本分析脚本（本地化Harness Cloud Cost Management等效方案）
# 分析双服务器资源使用率和成本优化建议
# ============================================================

MAIN_PEM="/Users/apple/Downloads/我的密钥.pem"
MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MAIN_SERVER="root@112.124.106.108"
MILVUS_SERVER="root@121.40.188.98"

red()    { echo -e "\033[31m$1\033[0m"; }
green()  { echo -e "\033[32m$1\033[0m"; }
yellow() { echo -e "\033[33m$1\033[0m"; }
blue()   { echo -e "\033[34m$1\033[0m"; }

echo "============================================================"
echo "  云成本分析报告  $(date '+%Y-%m-%d %H:%M')
============================================================"

# ---------- 主服务器 ----------
echo ""
blue "========== 主服务器 (112.124.106.108) =========="

MAIN_INFO=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "echo \"\$(free -m | awk '/Mem/{print \$2}')|\$(free -m | awk '/Mem/{print \$3}')|\$(free -m | awk '/Mem/{print \$4}')|\$(df / | awk 'NR==2{print \$2\"|\"\$3\"|\"\$5}')|\$(nproc)\"")
MAIN_MEM_TOTAL=$(echo $MAIN_INFO | cut -d'|' -f1)
MAIN_MEM_USED=$(echo $MAIN_INFO | cut -d'|' -f2)
MAIN_MEM_FREE=$(echo $MAIN_INFO | cut -d'|' -f3)
MAIN_DISK_TOTAL=$(echo $MAIN_INFO | cut -d'|' -f4)
MAIN_DISK_USED=$(echo $MAIN_INFO | cut -d'|' -f5)
MAIN_DISK_PCT=$(echo $MAIN_INFO | cut -d'|' -f6)
MAIN_CPU=$(echo $MAIN_INFO | cut -d'|' -f7)

MAIN_MEM_PCT=$((MAIN_MEM_USED * 100 / MAIN_MEM_TOTAL))

echo "CPU核心:  ${MAIN_CPU}"
echo "内存:    ${MAIN_MEM_USED}M / ${MAIN_MEM_TOTAL}M (${MAIN_MEM_PCT}%)"
echo "磁盘:    ${MAIN_DISK_USED}K / ${MAIN_DISK_TOTAL}K (${MAIN_DISK_PCT})"

# 主服务器服务
echo "运行服务:"
REDIS_MEM=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "/usr/local/redis/bin/redis-cli info memory 2>/dev/null | grep used_memory_rss | cut -d: -f2 | tr -d '\r'")
RABBIT_MEM=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "rabbitmqctl status 2>/dev/null | grep memory | head -1" || echo "unknown")
NGINX_MEM=$(ssh -i "$MAIN_PEM" $MAIN_SERVER "ps aux | grep 'nginx: worker' | grep -v grep | awk '{sum+=\$6} END{printf \"%.0fKB\", sum}'")
echo "  Redis:     ${REDIS_MEM}"
echo "  RabbitMQ:  ${RABBIT_MEM}"
echo "  Nginx:     ${NGINX_MEM}"

# ---------- Milvus服务器 ----------
echo ""
blue "========== Milvus服务器 (121.40.188.98) =========="

MILVUS_INFO=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "echo \"\$(free -m | awk '/Mem/{print \$2}')|\$(free -m | awk '/Mem/{print \$3}')|\$(free -m | awk '/Mem/{print \$4}')|\$(df / | awk 'NR==2{print \$2\"|\"\$3\"|\"\$5}')|\$(nproc)\"")
MILVUS_MEM_TOTAL=$(echo $MILVUS_INFO | cut -d'|' -f1)
MILVUS_MEM_USED=$(echo $MILVUS_INFO | cut -d'|' -f2)
MILVUS_MEM_PCT=$((MILVUS_MEM_USED * 100 / MILVUS_MEM_TOTAL))

echo "CPU核心:  ${MILVUS_CPU}"
echo "内存:    ${MILVUS_MEM_USED}M / ${MILVUS_MEM_TOTAL}M (${MILVUS_MEM_PCT}%)"
echo "磁盘:    ${MILVUS_DISK_USED}K / ${MILVUS_DISK_TOTAL}K (${MILVUS_DISK_PCT})"

# Milvus服务器Java进程
echo "Java服务:"
ssh -i "$MILVUS_PEM" $MILVUS_SERVER "ps aux | grep 'java.*chat' | grep -v grep | awk '{printf \"  %-20s RSS=%.0fMB\n\", \$13, \$6/1024}'"

# Docker容器
echo "Docker容器:"
ssh -i "$MILVUS_PEM" $MILVUS_SERVER "docker stats --no-stream --format '  {{.Name}}: {{.MemUsage}}' 2>/dev/null | head -10"

# ---------- 成本优化建议 ----------
echo ""
blue "========== 成本优化建议 =========="

# 内存使用率分析
if [ $MAIN_MEM_PCT -gt 85 ]; then
    red "⚠ 主服务器内存使用率 ${MAIN_MEM_PCT}%，建议升级内存或迁移服务"
elif [ $MAIN_MEM_PCT -gt 70 ]; then
    yellow "△ 主服务器内存使用率 ${MAIN_MEM_PCT}%，接近告警阈值"
else
    green "✓ 主服务器内存使用率 ${MAIN_MEM_PCT}%，健康"
fi

if [ $MILVUS_MEM_PCT -gt 85 ]; then
    red "⚠ Milvus服务器内存使用率 ${MILVUS_MEM_PCT}%，建议升级内存"
elif [ $MILVUS_MEM_PCT -gt 70 ]; then
    yellow "△ Milvus服务器内存使用率 ${MILVUS_MEM_PCT}%，偏高"
else
    green "✓ Milvus服务器内存使用率 ${MILVUS_MEM_PCT}%，健康"
fi

# Java进程内存分析
JAVA_TOTAL=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "ps aux | grep 'java.*chat' | grep -v grep | awk '{sum+=\$6} END{print sum/1024}'")
echo ""
yellow "Java服务总内存占用: ${JAVA_TOTAL}MB"
if [ $(echo "$JAVA_TOTAL > 4096" | bc -l 2>/dev/null || echo 0) = "1" ]; then
    echo "  建议: Java服务总内存超过4G，考虑缩减堆配置或合并低频服务(games/media)"
fi

# 磁盘分析
echo ""
yellow "磁盘使用:"
MAIN_DISK_NUM=$(echo $MAIN_DISK_PCT | tr -d '%')
MILVUS_DISK_NUM=$(echo $MILVUS_DISK_PCT | tr -d '%')
if [ $MAIN_DISK_NUM -gt 80 ]; then red "  主服务器磁盘 ${MAIN_DISK_PCT}，需要清理"; else green "  主服务器磁盘 ${MAIN_DISK_PCT}，正常"; fi
if [ $MILVUS_DISK_NUM -gt 80 ]; then red "  Milvus服务器磁盘 ${MILVUS_DISK_PCT}，需要清理"; else green "  Milvus服务器磁盘 ${MILVUS_DISK_PCT}，正常"; fi

# 闲置服务检测
echo ""
yellow "服务利用率:"
for port_name in "9090:core" "8081:web" "8083:games" "8084:media"; do
    port=$(echo $port_name | cut -d: -f1)
    name=$(echo $port_name | cut -d: -f2)
    qps=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "grep '$(date +%Y-%m-%d)' /opt/app/logs/chat-$name.log 2>/dev/null | wc -l")
    if [ "$qps" -lt 100 ]; then
        yellow "  $name: 今日仅 $qps 条日志，利用率极低"
    else
        green "  $name: 今日 $qps 条日志，正常"
    fi
done

# ECS付费方式建议
echo ""
yellow "ECS付费方式建议:"
echo "  两台ECS长期运行，建议从按量付费改为包年包月，可节省40-60%"
echo "  主服务器(1.6G): 适合 ecs.t6-burst1.small 包月约¥30/月"
echo "  Milvus服务器(7.3G): 适合 ecs.g6.large 包月约¥120/月"

echo ""
echo "============================================================"
echo "  分析完成 $(date '+%H:%M:%S')
============================================================"
