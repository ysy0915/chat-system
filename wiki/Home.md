# 博思AI智能体 3.0 — 项目 Wiki

多 AI 模型群聊系统，支持公开群聊、个人对话、观点辩论、情绪树洞、多模态生成（图片/视频/3D）和 AI 多人游戏。

> 本文档由项目 `docs/` 文档中心导出为 GitHub Wiki 结构（2026-08-14）。
> 仓库内文档源位于 `docs/`，此 `wiki/` 目录用于发布到 GitHub Wiki 仓库。

## 快速导航

| 分类 | 核心文档 |
|------|---------|
| 架构设计 | [[架构全盘说明]]（总纲）、[[ADR-架构决策记录]] |
| API 与数据库 | [[api-design]]、[[数据库设计说明]] |
| 运维部署 | [[部署运维手册]]、[[故障排查指南]] |
| 安全合规 | [[安全配置说明]] |
| 测试与质量 | [[测试规范]]、[[压测与优化报告_20260811]] |
| 交付材料 | [[项目概述]] |
| 变更与经验 | [[CHANGELOG-3.0]] |

**推荐阅读顺序**：项目概述 → 架构全盘说明 → 数据库设计说明 → 部署运维手册 → 安全配置说明 → 测试规范。

---

## 项目简介

### 技术栈

| 层级 | 技术 |
|------|------|
| **后端框架** | Spring Boot 3, Spring Cloud (Nacos), Spring Data Redis, MyBatis, gRPC |
| **AI 引擎** | chat-llm 独立 LLM 服务（多 Provider：OpenAI 兼容 / DeepSeek / 豆包 / OpenAI SDK）+ 自研 LangGraph 风格图执行引擎；chat-core 保留 LangChain4j 个人对话 / 树洞服务 |
| **知识库** | Milvus 向量数据库 + Embedding + RAG 检索增强 |
| **消息中间件** | RabbitMQ (跨节点广播, 聊天分流) |
| **数据库** | MySQL + Redis + Neo4j |
| **可观测性** | 熔断器、错误聚合、调用链追踪、自愈服务 |
| **前端** | React 18 + Vite + Router v6 + Axios + WebSocket |
| **部署** | Docker + Docker Compose (开发/生产/全部三套环境) |

### 项目结构

```
chat-system-project/
├── chat-common/       # 公共库（实体、DTO、安全、工具、拦截器）
├── chat-core/         # 核心 AI 服务（业务编排、Agent工具、意图识别）      端口 9090(主)/9092(从)，生产双实例
├── chat-web/          # Web 接入层（Controller、WebSocket）              端口 8080(本地) / 8081+8082(生产双实例)
├── chat-llm/          # 独立 LLM 服务（多 Provider、图执行引擎、RAG、知识图谱、gRPC） 端口 9095 / gRPC 9195
├── chat-games/        # 游戏服务（城堡围攻、乒乓、贪吃蛇）                 端口 8083
├── chat-media/        # 多模态服务（文生图、文生视频、图生3D）             端口 8084
├── frontend/          # 前端 SPA（React + Vite）
├── scripts/           # 运维脚本（部署、重启、监控、迁移）
└── docs/              # 完整文档（部署运维手册 + Prometheus 生产配置 + Nginx + 排障等）
```

### 功能全景

- **AI 伙伴群聊** — 多 AI 模型同时参与公开对话，支持流式输出
- **个人对话空间** — JWT 认证私密对话，历史持久化，知识问答自动触发 RAG
- **观点辩论场** — 多模型随机组队辩论（3~6 个可自选，名称中文展示），每轮反思修正
- **Multi-Agent 并行工作流** — 超长请求拆解 ≤9 子任务，RabbitMQ 分发双 core 10 并发 Worker 并行执行，Redis Lua 原子限流，DLX 死信重试 + Reconciler 对账兜底
- **情绪树洞** — 匿名倾诉 + AI 共情回复 + Memory 用户画像记忆
- **多模态生成** — 文生图 / 文生视频 / 图生 3D（GLB/OBJ/STL）
- **Agent 工具调用** — Calculator、Weather、Time、KnowledgeSearch 四工具
- **知识库 RAG** — Milvus 向量检索 + 文档解析 + 对话记忆融合（已迁移至 chat-llm）
- **知识图谱** — Neo4j 实体/关系存储 + LLM 三元组抽取（已迁移至 chat-llm）
- **多语言（i18n）** — 全站一键中英文切换，600+ 词条；游戏 / 管理后台 / 私聊动态文本同步翻译

---

## 快速开始

### 环境要求

- JDK 17+，Maven 3.8+，Node.js 18+
- MySQL 8.0, Redis 6+, RabbitMQ 3.9+
- Milvus 2.3+（可选，知识库功能需要）

### 本机开发

```bash
# 1. 启动基础设施
brew services start redis
brew services start rabbitmq

# 2. 打包
mvn clean install -DskipTests

# 3. 启动 chat-llm（LLM 独立服务，需先于 core 启动）
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

### LLM 独立部署（standalone 纯内存模式）

chat-llm 支持**零外部依赖独立部署**：无需 MySQL/Redis/Neo4j/Milvus，模型管理面、RAG 检索、对话记忆、知识图谱四大能力全部使用纯内存实现，单个进程即可完整体验。适合本地演示、单机验证或作为通用 LLM 网关使用。

#### JAR 启动

```bash
# 1. 打包（只需 chat-llm 及其依赖 chat-common）
mvn clean install -DskipTests -pl chat-llm -am

# 2. 配置 API Key（需要哪个厂商配哪个，不配则该厂商不可用但应用正常启动）
export DEEPSEEK_API_KEY=sk-xxx      # DeepSeek
export QWEN_API_KEY=sk-xxx          # 千问（RAG 向量化缺省也复用此 Key）
export DOUBAO_API_KEY=sk-xxx        # 豆包
# 可选：单独指定向量化 Key（缺省回退 QWEN_API_KEY）
export EMBEDDING_API_KEY=sk-xxx

# 3. 启动 standalone（HTTP 9095 / gRPC 9195）
java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=standalone --server.port=9095
```

#### Docker 启动

```bash
# 构建镜像（Dockerfile-llm 多阶段构建，无需本机 Maven/JDK）
docker build -f Dockerfile-llm -t chat-llm:latest .

# 启动容器（注入 API Key 环境变量）
docker run -d --name chat-llm \
  -p 9095:9095 -p 9195:9195 \
  -e DEEPSEEK_API_KEY=sk-xxx \
  -e QWEN_API_KEY=sk-xxx \
  chat-llm:latest
```

#### 验证

```bash
# 健康检查
curl http://localhost:9095/actuator/health

# 模型管理面：动态添加 Provider（apiKey 直接写入，存内存）
curl -X POST http://localhost:9095/api/v1/llm/admin/providers \
  -H "Content-Type: application/json" \
  -d '{"providerName":"ollama","providerType":"rest","baseUrl":"http://localhost:11434/v1","apiKey":"","models":[{"modelName":"llama3","displayName":"Llama 3"}]}'

# LLM 对话（provider/model 在请求体指定，非启动参数）
curl -X POST http://localhost:9095/api/v1/chain/invoke \
  -H "Content-Type: application/json" \
  -d '{"provider":"qwen","model":"qwen-plus","messages":[{"role":"user","content":"你好"}]}'

# SSE 流式调用
curl -N -X POST http://localhost:9095/api/v1/chain/stream \
  -H "Content-Type: application/json" \
  -d '{"provider":"qwen","model":"qwen-plus","messages":[{"role":"user","content":"写一首关于秋天的诗"}]}'
```

#### 端口说明

| 端口 | 协议 | 用途 |
|------|------|------|
| 9095 | HTTP | REST API（对话、模型管理面、RAG、知识图谱） |
| 9195 | gRPC | 图执行引擎 gRPC 接口（LangGraph 编排） |

> **注意**：内存数据**重启即清空**，仅适合本地演示 / 单机验证；生产请使用 `local` 或 `prod` profile 并接入 MySQL/Redis/Milvus/Neo4j。完整说明与全部 curl 示例见 `chat-llm/STANDALONE.md`。

### Docker 部署

```bash
docker-compose up -d                        # 开发环境
docker-compose -f docker-compose.yml -f docker-compose.prod.yml up -d   # 生产
```

---

## 工程指标

| 维度 | 指标 |
|------|------|
| 测试 | 全量 **892 用例全绿**（含集成测试、Mapper 契约测试 32 例） |
| 代码规范 | Checkstyle 0 违规 · PMD 92 · SpotBugs 0 阻断 |
| 架构设计 | 双 core/双 web 高可用 + Multi-Agent 并行工作流（DLX 死信重试 + Reconciler 对账） |
| 模型抽象 | SPI 策略工厂 + 动态路由 + 工具平台化 + 存储 SPI 热插拔 |
| 可观测性 | Prometheus 监控栈（12 条告警规则）+ 全链路追踪 |
| 文档 | 7 类文档中心 + ADR 25 条 + Swagger |
| CI/CD | GitHub Actions CI + Deploy + Security + OWASP |
| 安全性 | JWT 弱密钥校验 + 三层限流 + 内容安全过滤 |

详见 [[架构评估报告]]。
