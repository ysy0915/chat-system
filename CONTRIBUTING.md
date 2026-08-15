# 贡献指南（Contributing Guide）

感谢你对博思AI智能体的关注！欢迎通过提交 Issue、Pull Request 或文档改进参与贡献。

## 快速开始

在贡献前，请先阅读 [README.md](README.md) 了解项目定位与架构。

### 本地开发环境

```bash
# 1. 启动中间件（MySQL / Redis / RabbitMQ / Nacos / Milvus / Neo4j）
docker compose --profile dev up -d

# 2. 编译
mvn clean install -DskipTests

# 3. 启动核心服务（可选，二选一）
java -jar chat-core/target/chat-core-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

# 4. 前端
cd frontend && npm install && npm run dev
```

完整的一键启动见 [docker-compose.yml](docker-compose.yml) 与 [scripts/](scripts/) 目录。

## 如何贡献

### 报告 Bug

1. 使用 Bug Report 模板创建 Issue（`New Issue → Bug report`）。
2. 请务必包含：运行环境（OS/JDK 版本）、复现步骤、期望行为 vs 实际行为、相关日志。
3. 涉及多模态/推理的 Bug，请附上最小可复现输入。

### 提交功能 / 修复

1. Fork 本仓库并创建特性分支：`git checkout -b feat/xxx` 或 `fix/xxx`。
2. 遵循现有代码风格（已配置 Checkstyle / PMD / SpotBugs）。
3. 为改动补充单元测试，确保 `mvn test` 全量通过。
4. 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范：
   - `feat(模块): 描述`
   - `fix(模块): 描述`
   - `docs: 描述`
   - `refactor(模块): 描述`
5. 提交 PR 前运行：
   ```bash
   mvn clean test          # 全量测试（892 用例）
   bash scripts/sync-wiki.sh check   # 文档同步一致性（改动文档时）
   ```
6. 描述清楚改动背景、方案与测试情况。

### 改进文档

- 文档源在 `docs/` 目录，`wiki/` 由 [scripts/sync-wiki.sh](scripts/sync-wiki.sh) 自动导出。
- **请只修改 `docs/` 下的文档**，然后运行 `bash scripts/sync-wiki.sh` 同步 wiki。
- 架构决策请先阅读 [docs/01-架构设计/ADR-架构决策记录.md](docs/01-架构设计/ADR-架构决策记录.md)，重大变更需新增 ADR。

## 代码规范

| 工具 | 说明 |
|---|---|
| Checkstyle | 代码风格（`mvn checkstyle:check`） |
| PMD | 静态分析（`mvn pmd:check`） |
| SpotBugs | 潜在缺陷（`mvn spotbugs:check`） |
| JUnit 5 + Mockito | 单元测试 |

## 分支与发布

- `master`：主分支，始终可构建。
- 特性分支：`feat/`、`fix/`、`docs/`、`refactor/` 前缀。
- 每个 PR 需通过 CI（GitHub Actions）才会被合并。

## 行为准则

请遵守 [Code of Conduct](CODE_OF_CONDUCT.md)，保持友善、专业的交流氛围。

再次感谢你的贡献！
