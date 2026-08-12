#!/usr/bin/env python3
"""批量精简 restart-*.sh 脚本中的命令行参数"""
import re

# 需要从 java 命令行中删除的参数模式（这些已由 Nacos 配置中心提供）
REMOVE_PATTERNS = [
    r'\s*--spring\.cloud\.nacos\.discovery\.server-addr=[^\s\\]+\s*\\\n',
    r'\s*--spring\.cloud\.nacos\.discovery\.ip=[^\s\\]+\s*\\\n',
    r'\s*--spring\.cloud\.nacos\.discovery\.enabled=\w+\s*\\\n',
    r'\s*--spring\.cloud\.consul\.enabled=\w+\s*\\\n',
    r'\s*--spring\.datasource\.url=[^\s\\]+\s*\\\n',
    r'\s*--spring\.datasource\.username=[^\s\\]+\s*\\\n',
    r'\s*--spring\.datasource\.password=[^\s\\]+\s*\\\n',
    r'\s*--spring\.data\.redis\.host=[^\s\\]+\s*\\\n',
    r'\s*--spring\.data\.redis\.port=[^\s\\]+\s*\\\n',
]

scripts = {
    '/opt/app/restart-core.sh': True,
    '/opt/app/restart-web.sh': True,
    '/opt/app/restart-games.sh': True,
    '/opt/app/restart-media.sh': True,
    '/opt/app/restart-llm.sh': True,
}

for path, _ in scripts.items():
    with open(path, 'r') as f:
        content = f.read()
    original = content
    for pattern in REMOVE_PATTERNS:
        content = re.sub(pattern, '', content)
    if content != original:
        with open(path, 'w') as f:
            f.write(content)
        print(f'{path}: 已精简')
    else:
        print(f'{path}: 无变化')
