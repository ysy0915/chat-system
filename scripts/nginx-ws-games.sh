#!/bin/bash
# 在 location / 之前插入游戏 WebSocket 路由
sed -i '/location \/api\/v1\/games\//i\
        # 游戏 WebSocket → Milvus 服务器\
        location /ws/games {\
            proxy_pass http://172.23.172.13:8083/ws/chat;\
            proxy_set_header Host $host;\
            proxy_set_header X-Real-IP $remote_addr;\
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\
            proxy_http_version 1.1;\
            proxy_set_header Upgrade $http_upgrade;\
            proxy_set_header Connection "upgrade";\
            proxy_read_timeout 3600s;\
            proxy_send_timeout 3600s;\
        }\
' /etc/nginx/nginx.conf

nginx -t && nginx -s reload && echo "OK"
