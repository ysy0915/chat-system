# Scripts 目录

所有运维脚本统一放在此目录。

## 分类说明

### 部署相关
| 脚本 | 说明 |
|------|------|
| `deploy.sh` | 一键CI/CD部署（前端+后端+重启+健康检查） |
| `restart-all.sh` | 重启全部服务 |
| `restart-core.sh` | 重启core服务 |
| `restart-web.sh` | 重启web服务 |
| `restart-games.sh` | 重启games服务 |
| `restart-media.sh` | 重启media服务 |
| `restart-all-after-resize.sh` | 服务器扩容后重启全部 |

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
