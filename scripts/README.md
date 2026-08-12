# Scripts 目录

所有运维脚本统一放在此目录。

## 分类说明

### 部署相关
| 脚本 | 说明 |
|------|------|
| `deploy.sh` | **一键自动化部署**（安装+构建+上传+重启+健康检查），支持双 core/双 web/llm 与单模块部署 |
| `install-server.sh` | 服务器端一键安装（幂等）：JDK17/Docker/中间件(Nacos/Neo4j/Milvus)/Prometheus监控栈/.env，`--main` 安装主服务器 |
| `restart-all.sh` | 重启全部服务 |
| `restart-core.sh` | 重启core服务（支持 9090/9092/all） |
| `restart-web.sh` | 重启web服务（双实例 8081/8082） |
| `restart-games.sh` | 重启games服务 |
| `restart-media.sh` | 重启media服务 |
| `restart-llm.sh` | 重启llm服务 |
| `restart-all-after-resize.sh` | 服务器扩容后重启全部 |

### 一键部署用法（开发机执行）

```bash
# 全新上线：安装两台服务器环境 + 构建 + 上传 + 启动 + 健康检查
bash deploy.sh install-all

# 首次只装 Milvus 服务器环境（Java服务 + 中间件 + 监控栈）
bash deploy.sh install
# 首次只装主服务器环境（Nginx/Redis/RabbitMQ/前端目录）
bash deploy.sh install --main   # 或 bash deploy.sh install-main

# 日常全量部署（前端 + 全部后端）
bash deploy.sh all

# 仅前端
bash deploy.sh frontend
# 仅单个后端模块（双实例/单实例自动识别）
bash deploy.sh core    # 9090 + 9092 双实例
bash deploy.sh web     # 8081 + 8082 双实例
bash deploy.sh llm     # 9095
bash deploy.sh games   # 8083
bash deploy.sh media   # 8084
```

> 服务器上首次安装后需编辑 `/opt/app/.env` 填写真实凭据（DB/Redis/LLM Key），再执行 `bash deploy.sh all`。

### 本地开发
| 脚本 | 说明 |
|------|------|
| `start.sh` | 本机启动全部服务 |
| `start-core.sh` | 本机启动core |
| `start-web.sh` | 本机启动web |
| `start-games.sh` | 本机启动games |
| `start-media.sh` | 本机启动media |
| `start-background.sh` | 后台启动全部 |
| `stop-background.sh` | 停止后台服务 |
| `daemon-core.sh` | core守护进程 |

### Nginx配置
| 脚本 | 说明 |
|------|------|
| `nginx-patch.sh` | Nginx补丁配置 |
| `nginx-web-split.sh` | Nginx前后端分离配置 |
| `nginx-ws-games.sh` | Nginx games WebSocket配置 |

### 运维监控（Harness等效方案）
| 脚本 | 说明 |
|------|------|
| `cost-analysis.sh` | 云成本分析（Cloud Cost Management） |
| `chaos-test.sh` | 混沌工程测试（Resilience Testing） |
| `security-scan.sh` | 安全扫描（STO） |
| `sre-monitor.sh` | SRE智能监控告警（AI SRE） |
| `db-migrate.sh` | 数据库迁移（Database DevOps） |

### 数据初始化
| 脚本 | 说明 |
|------|------|
| `insert_model_configs_from_env.sh` | 从环境变量导入模型配置 |
