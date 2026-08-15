#!/usr/bin/env python3
"""清理 Nacos chat-common-prod.yml 中的明文默认密码/密钥。
将 ${VAR:明文} 改为 ${VAR:}（值只从服务器 .env 注入）。
用法（在 Milvus 服务器上执行）: python3 fix-nacos-secret.py
"""
import urllib.request, urllib.parse

NACOS = 'http://127.0.0.1:8848'
DATA_ID = 'chat-common-prod.yml'
GROUP = 'CHAT'

# 1. 读取当前配置
get_url = f'{NACOS}/nacos/v1/cs/configs?dataId={DATA_ID}&group={GROUP}'
content = urllib.request.urlopen(get_url, timeout=10).read().decode('utf-8')

# 2. 替换明文默认值
replacements = [
    ('${DB_PASSWORD:YangSy@0915!}', '${DB_PASSWORD:}'),
    ('${MQ_PASSWORD:chatapp2024}', '${MQ_PASSWORD:}'),
    ('${JWT_SECRET:k8Xp2mQ7vR3nF9wL5tY1bJ6cH4dA0eG8iS2uW7xZ}', '${JWT_SECRET:}'),
]
for old, new in replacements:
    if old not in content:
        print('WARNING: 未找到 ->', old[:40])
    content = content.replace(old, new)

# 3. 发布回 Nacos
pub_url = f'{NACOS}/nacos/v1/cs/configs'
data = urllib.parse.urlencode({
    'dataId': DATA_ID,
    'group': GROUP,
    'content': content,
}).encode('utf-8')
req = urllib.request.Request(pub_url, data=data, method='POST')
resp = urllib.request.urlopen(req, timeout=10).read().decode('utf-8')
print('发布结果:', resp)

print('=== 校验：替换后的敏感行 ===')
for line in content.split('\n'):
    if 'password:' in line or 'secret:' in line:
        print(line.strip())
