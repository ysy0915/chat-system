# CI/CD 流水线说明

## 一、流水线总览

```
┌──────────────────────────────────────────────────────────────┐
│                       GitHub Actions                         │
│                                                              │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │  CI (push)  │  │  Deploy      │  │  Security         │   │
│  │  .github/   │  │  .github/    │  │  .github/         │   │
│  │  workflows/ │  │  workflows/  │  │  workflows/       │   │
│  │  ci.yml     │  │  deploy.yml  │  │  security.yml     │   │
│  └──────┬──────┘  └──────┬───────┘  └────────┬──────────┘   │
│         │                │                   │               │
│         ▼                ▼                   ▼               │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ 前端构建  │    │  手动触发部署  │    │ 定时安全扫描  │       │
│  │ 后端编译  │    │  ↓           │    │ Gitleaks     │       │
│  │ 单测+覆盖 │    │  scp →服务器  │    │ Dep-Check    │       │
│  │ Checkstyle│    │  restart.sh  │    │ npm audit    │       │
│  │ PMD       │    │  health check│    │ SpotBugs     │       │
│  │ SpotBugs  │    └──────────────┘    └──────────────┘       │
│  └──────────┘                                                │
└──────────────────────────────────────────────────────────────┘
```

## 二、三条流水线

### 2.1 CI 流水线 (`ci.yml`)

**触发条件**: push 到 `main` / `develop`，或 PR 到 `main`

**Job 矩阵**:

| Job | 内容 | 耗时 |
|-----|------|------|
| `frontend` | npm ci → npm run build → 上传 dist 产物 | ~2min |
| `backend` | Checkstyle → PMD → Maven test → SpotBugs → 上传 4 份报告 | ~5min |
| `summary` | 汇总前后端结果，任一失败则整体失败 | 秒级 |

**后端 Job 详细阶段**:

```
Checkstyle (快速失败) → PMD (快速失败) → mvn test -Pci → SpotBugs → 上传报告
```

**上传的报告 (保留 14 天)**:
- `jacoco-coverage` — 覆盖率 HTML
- `spotbugs-report` — 静态漏洞 XML
- `checkstyle-report` — 代码风格 XML
- `pmd-report` — 代码质量 XML

### 2.2 部署流水线 (`deploy.yml`)

**触发条件**: 手动触发 (`workflow_dispatch`)，可选择部署目标

**可选参数**:

| 参数 | 选项 | 说明 |
|------|------|------|
| `target` | `all` / `frontend` / `core` / `web` / `games` / `media` | 选择部署哪些模块 |
| `skip_tests` | `true` / `false` | 跳过测试加快部署 |

**部署流程**:

```
Checkout → Build (frontend + maven) → Upload artifacts
    │
    ▼
Deploy Job:
    ├─ frontend: scp dist/ → your-nginx-ip:/opt/app/static/chat/
    ├─ core:     scp jar  → your-milvus-ip:/opt/app/core/ → restart-core.sh → health :9090
    ├─ web:      scp jar  → your-milvus-ip:/opt/app/web/  → restart-web.sh  → health :8081
    ├─ games:    scp jar  → your-milvus-ip:/opt/app/games/ → restart-games.sh
    ├─ media:    scp jar  → your-milvus-ip:/opt/app/media/ → restart-media.sh
    └─ 汇总报告
```

**健康检查机制**:
- 每 2 秒轮询 `/actuator/health`
- 最多等待 60 秒（30 次 × 2 秒）
- 任一服务健康检查失败 → 部署标记失败

### 2.3 安全流水线 (`security.yml`)

**触发条件**: 
- 每周一凌晨 2:00 UTC 自动扫描
- push 到 main/develop 时（依赖文件变更时）
- 手动触发

| Job | 工具 | 作用 |
|-----|------|------|
| `gitleaks` | Gitleaks Action v2 | 扫描提交历史中的密钥/Token/密码泄露 |
| `dependency-check` | OWASP Dep-Check Maven | Java 依赖 CVE 漏洞数据库比对 |
| `npm-audit` | npm audit | 前端依赖已知漏洞 |
| `security-summary` | — | 汇总三项扫描结果 |

## 三、所需 GitHub Secrets

部署流水线依赖以下 Secrets 在仓库中配置：

| Secret 名 | 值 | 用途 |
|-----------|-----|------|
| `MAIN_HOST` | `your-nginx-ip` | 主服务器 (Nginx) IP |
| `MAIN_SSH_KEY` | `~/.ssh/主服务器私钥内容` | 前端部署 SSH 认证 |
| `MAIN_KNOWN_HOSTS` | `ssh-keyscan 输出` | 主服务器 host key |
| `MILVUS_HOST` | `your-milvus-ip` | Milvus 服务器 IP |
| `MILVUS_SSH_KEY` | `~/.ssh/Milvus服务器私钥内容` | 后端部署 SSH 认证 |
| `MILVUS_KNOWN_HOSTS` | `ssh-keyscan 输出` | Milvus 服务器 host key |

**配置方法**:
```bash
# 获取 known_hosts
ssh-keyscan -H your-nginx-ip >> known_hosts_main
ssh-keyscan -H your-milvus-ip >> known_hosts_milvus

# 在 GitHub → Settings → Secrets and variables → Actions → New repository secret
```

## 四、本地与 CI 对应关系

| 本地命令 | CI 等价 |
|---------|---------|
| `mvn clean install -DskipTests` | CI `backend` Job 的 `mvn test -B -Pci` |
| `bash scripts/deploy.sh all` | Actions → Deploy workflow → target=all |
| `bash scripts/deploy.sh core` | Actions → Deploy workflow → target=core |
| `cd frontend && npm run build` | CI `frontend` Job |
| 手动 `scp` + `restart-*.sh` | Deploy workflow 自动完成 |

## 五、回滚策略

### 5.1 GitHub Actions 回滚

1. 找到上一次成功的 Deploy workflow run
2. 点击 "Re-run all jobs"
3. 这会使用上一次构建的 artifact 重新部署

### 5.2 手动回滚

```bash
# 1. 找到上一个版本的 jar（服务器上有备份）
ssh -i "Milvus.pem" root@your-milvus-ip "ls -lt /opt/app/core/*.jar.bak /opt/app/web/*.jar.bak /opt/app/llm/*.jar.bak"

# 2. 恢复 backup（以 chat-llm 为例）
ssh -i "Milvus.pem" root@your-milvus-ip "cp /opt/app/llm/chat-llm-{prev}.jar.bak /opt/app/llm/chat-llm-0.0.1-SNAPSHOT.jar"

# 3. 重启
ssh -i "Milvus.pem" root@your-milvus-ip "bash /opt/app/restart-llm.sh"
```

### 5.3 建议改进

- [x] ~~部署前自动备份当前 jar (`cp xxx.jar xxx.jar.bak.{timestamp}`)~~ — 已在 restart-*.sh 中实现
- [x] ~~OWASP Dependency-Check 集成到 CI 流水线~~ — 已在 ci.yml 阶段3.5 添加
- [ ] 添加 `deploy/rollback.yml` workflow 一键回滚
- [ ] 版本号从 `0.0.1-SNAPSHOT` 升级为带 commit hash 的格式

## 六、故障排查

| 现象 | 可能原因 | 解决 |
|------|---------|------|
| CI 前端 Job 失败 | npm ci 报错 | 检查 `package-lock.json` 是否最新 |
| CI 后端 Job 失败 (Checkstyle) | 代码风格不合规 | 本地运行 `mvn checkstyle:check` |
| 部署后健康检查超时 | 服务启动慢或 crash | SSH 进服务器查看 `tail -100 /opt/app/logs/app-*.log` |
| Deploy Job "Host key verification failed" | known_hosts 未配置 | 添加 `MILVUS_KNOWN_HOSTS` secret |
| Gitleaks 误报 | 文档中的示例密钥 | 在 `.gitleaks.toml` 添加文件路径白名单 |
