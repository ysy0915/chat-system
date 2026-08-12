#!/usr/bin/env python3
# 更新 Nacos 配置：RabbitMQ host 公网 -> 内网
import urllib.request, urllib.parse

BASE = 'http://127.0.0.1:8848/nacos/v1/cs/configs'
DATAID = 'chat-common-prod.yml'
GROUP = 'CHAT'

# 读取当前配置
req = urllib.request.Request(BASE + '?dataId=%s&group=%s' % (DATAID, GROUP))
content = urllib.request.urlopen(req).read().decode()

old = 'host: 112.124.106.108'
new = 'host: 172.18.160.222'
count = content.count(old)
print('found %d occurrence(s) of old host' % count)
if count == 0:
    print('WARN: nothing to replace, abort publish')
else:
    content = content.replace(old, new)
    data = urllib.parse.urlencode({'dataId': DATAID, 'group': GROUP, 'content': content}).encode()
    resp = urllib.request.urlopen(urllib.request.Request(BASE, data=data))
    print('publish result:', resp.read().decode())
