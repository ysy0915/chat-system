#!/bin/bash
# 在 location / 之前插入子服务路由
sed -i '/location \/ {/i\
        # 游戏服务 → Milvus 服务器\
        location /api/v1/games/ {\
            proxy_pass http://your-intra-ip:8083;\
            proxy_set_header Host $host;\
            proxy_set_header X-Real-IP $remote_addr;\
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\
            proxy_http_version 1.1;\
            proxy_set_header Upgrade $http_upgrade;\
            proxy_set_header Connection "upgrade";\
            proxy_read_timeout 3600s;\
        }\
\
        # SQL 执行器 → Milvus 服务器\
        location /api/v1/sql/ {\
            proxy_pass http://your-intra-ip:8083;\
            proxy_set_header Host $host;\
            proxy_set_header X-Real-IP $remote_addr;\
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\
        }\
\
        # 多模态服务 → Milvus 服务器\
        location /api/v1/media/ {\
            proxy_pass http://your-intra-ip:8084;\
            proxy_set_header Host $host;\
            proxy_set_header X-Real-IP $remote_addr;\
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\
            client_max_body_size 50m;\
        }\
' /etc/nginx/nginx.conf

nginx -t && nginx -s reload && echo "OK"
