#!/bin/bash
# 备份原配置
cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.bak.$(date +%s)

# 替换 location / 块为静态资源 + API 分离
cat > /tmp/nginx-location-patch.py << 'PYEOF'
import re

with open('/etc/nginx/nginx.conf', 'r') as f:
    content = f.read()

# 新的 location / 块：静态资源走文件系统，API 走后端
new_location = """        # 静态资源（前端 SPA）
        location / {
            root /opt/app/static/chat;
            index index.html;
            try_files $uri $uri/ /index.html;
        }

        # API 请求 → chat-core
        location /api/ {
            proxy_pass http://backend_nodes;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_connect_timeout 5s;
            proxy_next_upstream error timeout http_502 http_503 http_504;
            proxy_next_upstream_tries 2;
        }

        # WebSocket → chat-core
        location /ws/ {
            proxy_pass http://backend_nodes;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection "upgrade";
            proxy_read_timeout 3600s;
            proxy_send_timeout 3600s;
        }"""

# 替换原来的 location / 块
pattern = r'        location / \{[^}]*\}[^}]*\}[^}]*\}[^}]*\}[^}]*\}[^}]*\}'
content = re.sub(pattern, new_location, content, count=1, flags=re.DOTALL)

with open('/etc/nginx/nginx.conf', 'w') as f:
    f.write(content)

print("patched")
PYEOF

python3 /tmp/nginx-location-patch.py && nginx -t && nginx -s reload && echo "Nginx 配置更新成功"
