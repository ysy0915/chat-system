#!/bin/bash
# chat-core 守护脚本：检测 chat-core 挂掉后自动重启
# 用法：nohup bash /opt/app/daemon-core.sh > /opt/app/logs/daemon-core.log 2>&1 &

while true; do
    if ! curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/actuator/health 2>/dev/null | grep -q '200'; then
        echo "[$(date)] chat-core 不可用，正在重启..." 
        bash /opt/app/start-core.sh
        sleep 30
    fi
    sleep 10
done
