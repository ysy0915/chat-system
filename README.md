# Chat System Project

多 AI 模型群聊系统，支持公开群聊、个人对话、观点辩论、情绪树洞、多模态生成（图片/视频/3D）和 AI 多人游戏。

## 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3, Spring Cloud (Nacos), Spring Data Redis, MyBatis, gRPC |
| **AI 引擎** | chat-llm 独立 LLM 服务（多 Provider：OpenAI 兼容 / DeepSeek / 豆包 / OpenAI SDK）+ 自研 LangGraph 风格图执行引擎；chat-core 保留 LangChain4j 个人对话 / 树洞服务 |
| **知识库** | Milvus 向量数据库 + Embedding + RAG 检索增强 |
| **消息中间件** | RabbitMQ (跨节点广播, 聊天分流) |
| **数据库** | MySQL + Redis + Elasticsearch + Kafka + Flink |
| **可观测性** | 熔断器、错误聚合、调用链追踪、自愈服务 |
| **前端** | React 18 + Vite + Router v6 + Axios + WebSocket |
| **部署** | Docker + Docker Compose (开发/生产/全部三套环境) |

## 项目结构

```
chat-system-project/
├── chat-common/       # 公共库（实体、DTO、安全、工具、拦截器）
├── chat-core/         # 核心 AI 服务（业务编排、Agent工具、意图识别）      端口 9090(主)/9092(从)，生产双实例
├── chat-web/          # Web 接入层（Controller、WebSocket）              端口 8080(本地) / 8081+8082(生产双实例)
├── chat-llm/          # 独立 LLM 服务（多 Provider、图执行引擎、RAG、知识图谱、gRPC） 端口 9095 / gRPC 9195
├── chat-games/        # 游戏服务（城堡围攻、乒乓、贪吃蛇）                 端口 8083
├── chat-media/        # 多模态服务（文生图、文生视频、图生3D）             端口 8084
├── flink-log-analyzer/# 日志分析（Kafka → Flink → ES 实时流式处理）
├── frontend/          # 前端 SPA（React + Vite）
├── scripts/           # 运维脚本（部署、重启、监控、迁移）
└── docs/              # 完整文档（部署运维手册 + Prometheus 生产配置 + Nginx + 排障等）
```

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- Node.js 18+
- MySQL 8.0, Redis 6+, RabbitMQ 3.9+
- Milvus 2.3+ (可选，知识库功能需要)

### 本机开发

```bash
# 1. 启动基础设施
brew install redis rabbitmq
brew services start redis
brew services start rabbitmq

# 2. 打包
mvn clean install -DskipTests

# 3. 启动 chat-llm（LLM 独立服务：RAG / 知识图谱已迁移至此，需先于 core 启动）
java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=9095

# 4. 启动 chat-core（核心 AI 服务）
java -jar chat-core/target/chat-core-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=9090

# 5. 新终端启动 chat-web（Web 接入层）
java -jar chat-web/target/chat-web-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local --server.port=8080 \
  --app.core.base-url=http://127.0.0.1:9090

# 6. 启动前端
cd frontend && npm install && npm run dev
```

访问：
- 前端：http://localhost:5173
- API：http://localhost:8080/api/v1/*
- Core 内部 API：http://localhost:9090/internal/*

### Docker 部署

```bash
# 开发环境
docker-compose up -d

# 生产环境
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

---

## 功能模块

### AI 伙伴群聊
多 AI 模型同时参与公开对话，支持流式输出

### 个人对话空间
JWT 认证的私密对话，历史记录持久化，基于 LangChain4j ChatMemory，知识/事实类问题自动触发 RAG 检索增强（可查性三层判定）

### 观点辩论场
LangGraph4j 实现三 AI 并行辩论（豆包/千问/DeepSeek），场次可选（默认 3 轮，1~10），每轮反思修正 + 裁决式汇总

### Multi-Agent 并行工作流（2026-08）
超长/跨域请求自动拆解为最多 9 个子任务，经 RabbitMQ 分发到双 core 实例的 10 并发 Worker 并行执行，主 Agent 收敛压缩输出（≤1000 字）：
- **Redis Lua 原子限流** — 双实例共享 `max-concurrent=8` 并发上限，超限自动降级普通流程（不排队、不拒绝）
- **manual ack + prefetch=1** — 子任务按 Worker 真实能力公平分发，忙时不拉新消息；执行中实例挂掉由 RabbitMQ requeue 自动重投，零丢失
- **WorkflowReconciler 对账** — 每 30s 兜底"结果已齐但收敛未完成"的卡住 plan，服务器挂掉重启后自动恢复收敛
- **输出压缩** — 收敛用轻量模型（qwen-turbo）汇总，最终回答 ≤1000 字
- 压测结论：20 并发 = 8 并行 + 12 降级，全量测试套件 PASS=12 / FAIL=0（`scripts/test-multiagent-suite.sh`）

### 情绪树洞
匿名情绪倾诉，AI 共情回复，内容安全过滤，Memory 记忆增强：LLM 提炼用户画像（情景+情绪+偏好），回答逐步贴合个人偏好

### 多模态生成
- 文生图 / 文生视频
- 图生 3D 模型（GLB/OBJ/STL）

### Agent 工具调用
Calculator、Weather、Time、KnowledgeSearch 四工具，LLM 自主决定调用

### 知识库 RAG
Milvus 向量检索 + 文档解析 + 文本分块 + 对话记忆融合，**运行时已迁移至 chat-llm 独立服务**：
- chat-core 经 `RagClient` 跨进程调用 chat-llm `/internal/rag/*`（检索/向量化/记忆/RAG 回答）
- chat-web 知识库管理 API 经 `CoreClient` 代理到 chat-llm `/api/v1/rag/*`
- 两套 Embedding 并存：legacy 知识库 1024 维（`LegacyEmbeddingService`）与新版 RAG 1536 维（`EmbeddingService`）
- **对话自动 RAG**：个人对话与群聊中，知识问答/任务执行意图（含天气）自动检索默认知识库（`app.rag.chat.*`），相似度 ≥0.3 命中片段注入【参考资料】增强回答；知识库为空/未命中自动降级普通回答
- **可查性三层判定**：开关配置 → 意图判定（KNOWLEDGE_QA / TASK_EXECUTION）→ 排除实时/个人数据类查询（天气、时间、新闻、行情、订单等），避免无效检索

### 知识图谱
Neo4j 实体/关系存储 + LLM 三元组抽取，**已迁移至 chat-llm**（`KnowledgeGraphService` 编排 Neo4j 连接与抽取）

### 可观测性
- **熔断器** — 模型连续失败自动熔断，半开恢复
- **错误聚合** — 按模型/错误类型统计聚合
- **调用链追踪** — Micrometer Tracing + Brave + Zipkin 全链路追踪
- **自愈服务** — Resilience4j 熔断 + 重试 + 超时保护
- **监控面板** — 开发: Prometheus + Grafana (docker-compose --profile monitoring)；生产: Prometheus 栈已上线 Milvus 服务器（Prometheus:9094 · Alertmanager:9093 · node-exporter:9100 · 钉钉告警 webhook:9950，6 条告警规则替代手工巡检）
- **API 文档** — Swagger UI: http://localhost:8080/swagger-ui.html (开发)

---

## 运行测试

```bash
# 全量测试 (Mockito 5.14.2 + ByteBuddy 1.15.11, 兼容 JDK 26)
mvn clean test

# 单模块
mvn test -pl chat-common  # ✅ 51 个测试类（35 个空壳/废弃测试已清理）
mvn test -pl chat-core
mvn test -pl chat-web   # ✅ 54 个测试（12 个 Controller 全覆盖）
mvn test -pl chat-media
mvn test -pl chat-games
mvn test -pl chat-llm

# 跳过测试构建
mvn clean install -DskipTests
```

---

## 文档索引

### 架构与设计

| 文档 | 内容 |
|------|------|
| [docs/01-架构设计/架构全盘说明.md](docs/01-架构设计/架构全盘说明.md) | **总纲**：整体架构 → 模块细节 → 核心流程 → 数据流 → 部署（一册通览） |
| [docs/01-架构设计/架构评估报告.md](docs/01-架构设计/架构评估报告.md) | 整体系统评分 91/100 + 架构说明 + 风险路线图 |
| [docs/01-架构设计/系统架构说明.md](docs/01-架构设计/系统架构说明.md) | 前后端架构、数据流、调用链 |
| [docs/02-API与数据库/api-design.md](docs/02-API与数据库/api-design.md) | 全部 REST API 设计 |
| [docs/02-API与数据库/数据库设计说明.md](docs/02-API与数据库/数据库设计说明.md) | MySQL 表结构、Redis Key、索引策略 |
| [docs/01-架构设计/LLM策略与路由说明.md](docs/01-架构设计/LLM策略与路由说明.md) | LLM 策略、路由、容错、已知问题 |
| [docs/04-安全合规/安全配置说明.md](docs/04-安全合规/安全配置说明.md) | JWT 鉴权、弱密钥校验、三层限流、DTO 校验、内容安全 |
| [docs/07-变更与经验/CHANGELOG-3.0.md](docs/07-变更与经验/CHANGELOG-3.0.md) | 版本变更记录、模块分工 |

### 运维与排错

| 文档 | 内容 |
|------|------|
| [docs/03-运维部署/部署运维手册.md](docs/03-运维部署/部署运维手册.md) | 本机/服务器/Docker 部署全流程、监控告警 |
| [docs/prometheus-prod.yml](docs/prometheus-prod.yml) + [prometheus-alert-rules.yml](docs/prometheus-alert-rules.yml) | 生产监控抓取 + 告警规则 |
| [docs/03-运维部署/CI_CD.md](docs/03-运维部署/CI_CD.md) | GitHub Actions 流水线、自动部署、回滚策略 |
| [docs/03-运维部署/故障排查指南.md](docs/03-运维部署/故障排查指南.md) | 常见问题现象→根因→修复步骤 |

### 架构与设计

| 文档 | 内容 |
|------|------|
| [docs/01-架构设计/架构设计说明.md](docs/01-架构设计/架构设计说明.md) | LLM 调用架构、无状态化设计、多实例部署 |

### 质量与安全

| 文档 | 内容 |
|------|------|
| [docs/05-测试与质量/代码规范与质量说明.md](docs/05-测试与质量/代码规范与质量说明.md) | Checkstyle/PMD/SpotBugs 规范与使用 |
| [docs/05-测试与质量/测试规范.md](docs/05-测试与质量/测试规范.md) | 测试分层、Mock 策略、空壳清理计划 |
| [docs/04-安全合规/安全合规说明.md](docs/04-安全合规/安全合规说明.md) | 安全扫描、秘钥管理、合规检查清单 |

### 运维与配置

| 文档 | 内容 |
|------|------|
| [docs/nacos-shared-config.yaml](docs/nacos-shared-config.yaml) | Nacos 共享配置模板（LLM 动态参数 + Session 追踪） |

### 其他

| 文档 | 内容 |
|------|------|
| [docs/nginx.conf](docs/nginx.conf) | Nginx 反向代理完整配置 |
| [docs/db-migrations/](docs/db-migrations/) | 数据库版本化迁移脚本 |
| [docs/sql/](docs/sql/) | 功能模块 DDL 脚本 |

---

## 评分

| 维度 | 得分 | 说明 |
|------|:--:|------|
| 测试覆盖 | 24/25 | 源文件全面覆盖，真实测试持续扩充 |
| 测试质量 | 14/20 | ✅ 空壳测试全清理 + chat-web 全 Controller 测试（54 个）+ 前端 hooks 测试 |
| 代码规范 | 14/15 | ✅ Checkstyle 0违规, PMD 2000+→92, CI阻断就绪 |
| 架构设计 | 14/15 | ✅ 双 core/双 web 高可用 + stop 广播 + nodeId 防堆积 + LangGraph 混合编排 |
| 模型抽象与通用性 | 9.5/10 | ✅ Provider 策略+SPI 策略工厂(代码落地)+注册中心+动态路由+模型自助管理面(DB 持久化+即时生效+重载)+工具/存储接口抽象+配置分层；⚠️ 仅剩工具/存储元数据化 |
| 可观测性 | 4/5 | ✅ Prometheus 栈 6 条告警上线、指标化替代巡检；⚠️ 告警覆盖尚缺业务指标 |
| 文档 | 9/10 | ✅ 架构全盘说明 + 评估报告 + springdoc/Swagger + 部署运维手册 |
| CI/CD | 9/10 | ✅ GitHub Actions CI + Deploy + Security + OWASP |
| 安全性 | 5/5 | ✅ JWT弱密钥校验 + 三层限流 + DTO校验 + 上传限制 + CORS + CSP + OWASP |
| **综合** | **91/100** | ✅ 90→91（策略工厂 SPI 代码落地）→ 同日模型管理面落地（短板清零，评分维持）→ 达成目标，向 92 迈进 |
