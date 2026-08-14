# 系统设计文档 — ER 与 API 设计

> **版本演进**：§1-§15 为 V1.0 初始设计（历史存档），§16+ 为 V3.0 实际上线 API 全量清单（2026-08-15 更新）。
>
> 本文档包含 MySQL DDL、OpenAPI 概要、Redis 键设计、MQ 消息格式、WebSocket 流示例、完整 API 端点清单（146 个）、索引与运维/安全建议。

## 0 V3.0 完整 API 端点清单（2026-08-15）

> Base URL: `/api/v1`（chat-web 对外） / `/internal`（chat-core/chat-llm 内部）
> Security: `bearerAuth`（JWT），部分管理接口需 `X-Admin-Password` 或 `X-Admin-Pass` 头
> 统一错误响应：`{"ok":false,"code":<HTTP状态码>,"error":"<错误信息>"}`

### 0.1 chat-web（对外 REST API 网关，端口 8081）

#### 认证模块 `/api/v1/auth`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/auth/captcha` | 获取注册验证码（算术题，5分钟有效） | 公开 |
| POST | `/api/v1/auth/login` | 登录（连续5次失败锁15分钟） | 公开 |
| POST | `/api/v1/auth/register` | 注册（需验证码 token + answer） | 公开 |

#### 消息模块 `/api/v1/messages`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/messages` | 创建消息（AI 群聊/个人对话，触发流式回答） | JWT |
| POST | `/api/v1/messages/with-file` | 带文件上传的消息（multipart） | JWT |
| GET | `/api/v1/messages` | 消息列表 | JWT |
| GET | `/api/v1/messages/recent` | 最近私聊消息 | JWT |
| GET | `/api/v1/messages/search?keyword=` | 搜索私聊消息（全文索引） | JWT |
| GET | `/api/v1/messages/context?msg_id=` | 获取消息上下文 | JWT |
| GET | `/api/v1/messages/online-count?page=` | 在线人数（按页面） | 公开 |
| POST | `/api/v1/messages/regenerate` | 重新生成回答 | JWT |
| POST | `/api/v1/messages/stop` | 停止流式生成（广播到所有 core 实例） | JWT |

#### 情绪树洞 `/api/v1/treehole`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/treehole/history` | 树洞历史 | JWT |
| GET | `/api/v1/treehole/recent` | 最近树洞 | JWT |
| GET | `/api/v1/treehole/search?keyword=` | 搜索树洞 | JWT |
| GET | `/api/v1/treehole/context?msg_id=` | 树洞上下文 | JWT |
| POST | `/api/v1/treehole/ask` | 树洞提问（Memory 记忆增强） | JWT |
| POST | `/api/v1/treehole/ask-with-file` | 带文件树洞提问 | JWT |
| POST | `/api/v1/treehole/regenerate` | 重新生成树洞回答 | JWT |
| POST | `/api/v1/treehole/stop` | 停止树洞流式 | JWT |

#### 辩论模块 `/api/v1/debate`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/debate` | 启动辩论 `{topic, rounds, model_count, mode}` | JWT |

> `mode`: `standard`（线性）/ `tree`（树状）；`rounds` 1~10 默认 3；`model_count` 3~6 默认 3

#### 用户资料 `/api/v1/users`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/users` | 获取当前用户资料 | JWT |
| PUT | `/api/v1/users` | 更新用户资料 | JWT |

#### 模型列表 `/api/v1/models`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/models` | 可用模型列表（前端模型选择器/辩论组队） | 公开 |

#### 附件 `/api/v1/attachments`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/attachments` | 上传文件（multipart，≤10MB） | JWT |

#### 知识库 RAG `/api/v1/rag`（代理到 chat-llm）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/rag/kb` | 知识库列表 | admin |
| POST | `/api/v1/rag/kb` | 创建知识库 | admin |
| DELETE | `/api/v1/rag/kb/{id}` | 删除知识库 | admin |
| GET | `/api/v1/rag/kb/{id}/docs` | 文档列表 | admin |
| POST | `/api/v1/rag/kb/{id}/docs` | 上传文档（异步解析，返回 202） | admin |
| DELETE | `/api/v1/rag/documents/{docId}` | 删除文档 | admin |

#### 知识图谱 `/api/v1/graph`（代理到 chat-core → chat-llm）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/graph` | 获取图谱（节点+边，支持权重过滤） | JWT |
| GET | `/api/v1/graph/search?keyword=` | 搜索图谱 | JWT |
| GET | `/api/v1/graph/stats` | 图谱统计（实体数/关系数） | JWT |
| POST | `/api/v1/graph/import` | 触发批量导入 | JWT |
| GET | `/api/v1/graph/import/status` | 导入进度 | JWT |

#### 模型管理面 `/api/v1/llm/admin`（代理到 chat-llm）

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/llm/admin/login` | 管理员登录 | 公开 |
| GET | `/api/v1/llm/admin/providers` | Provider 列表（apiKey 脱敏） | 公开 |
| GET | `/api/v1/llm/admin/providers/types` | 策略工厂 supportedTypes | 公开 |
| POST | `/api/v1/llm/admin/providers` | 新增 Provider | X-Admin-Pass |
| PUT | `/api/v1/llm/admin/providers/{id}` | 更新 Provider | X-Admin-Pass |
| DELETE | `/api/v1/llm/admin/providers/{id}` | 删除 Provider（级联+路由卸载） | X-Admin-Pass |
| POST | `/api/v1/llm/admin/providers/reload` | 全量热重载 | X-Admin-Pass |

#### 监控面板 `/api/v1/monitor`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/monitor/login` | 监控面板登录 | 公开 |
| GET | `/api/v1/monitor/online-history?days=` | 在线人数历史 | X-Admin-Password |
| GET | `/api/v1/monitor/current` | 当前在线人数 | X-Admin-Password |
| POST | `/api/v1/monitor/record` | 记录当前在线快照 | X-Admin-Password |
| GET | `/api/v1/monitor/llm-stats?date=` | LLM 调用统计 | X-Admin-Password |
| GET | `/api/v1/monitor/total-usage` | 总使用量 | X-Admin-Password |
| GET | `/api/v1/monitor/traces?n=` | 最近调用链 | X-Admin-Password |
| GET | `/api/v1/monitor/errors` | 错误统计 | X-Admin-Password |
| GET | `/api/v1/monitor/traces/search?keyword=` | 搜索调用链 | X-Admin-Password |

#### IP 管理 `/api/v1/admin/ip`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/admin/ip/blacklist` | 黑名单列表 | X-Admin-Password |
| POST | `/api/v1/admin/ip/blacklist/{ip}` | 拉黑 IP | X-Admin-Password |
| DELETE | `/api/v1/admin/ip/blacklist/{ip}` | 解封 IP | X-Admin-Password |
| GET | `/api/v1/admin/ip/stats/{ip}` | IP 请求统计 | X-Admin-Password |

#### 服务健康 `/api/v1/health`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/health/games` | 游戏服务健康检查 | 公开 |

#### 前端错误上报 `/api/v1/frontend-error`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/frontend-error` | 前端异常上报 | 公开 |

---

### 0.2 chat-llm（AI 能力层，端口 9095 / gRPC 9195）

#### LLM 对话 `/api/v1/chain`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/chain/invoke` | 非流式调用 `{provider, model, messages}` | 公开 |
| POST | `/api/v1/chain/stream` | SSE 流式调用 | 公开 |
| POST | `/api/v1/chain/graph/invoke` | 图执行引擎非流式 | 公开 |
| POST | `/api/v1/chain/graph/stream` | 图执行引擎 SSE 流式 | 公开 |

#### LangGraph 引擎 `/api/v1/graph`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/graph/execute` | 图执行（同步） | 公开 |
| POST | `/api/v1/graph/stream` | 图执行（SSE 流式） | 公开 |
| GET | `/api/v1/graph/health` | 图引擎健康检查 | 公开 |

#### 模型管理面（直连） `/api/v1/llm/admin/providers`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/llm/admin/providers` | Provider 列表 | 公开 |
| GET | `/api/v1/llm/admin/providers/types` | 策略类型列表 | 公开 |
| POST | `/api/v1/llm/admin/providers` | 新增 | X-Admin-Pass |
| PUT | `/api/v1/llm/admin/providers/{id}` | 更新 | X-Admin-Pass |
| DELETE | `/api/v1/llm/admin/providers/{id}` | 删除 | X-Admin-Pass |
| POST | `/api/v1/llm/admin/providers/reload` | 热重载 | X-Admin-Pass |

#### RAG 知识库（直连） `/api/v1/rag`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/rag/kb` | 知识库列表 | Authorization |
| POST | `/api/v1/rag/kb` | 创建知识库 | Authorization |
| DELETE | `/api/v1/rag/kb/{id}` | 删除知识库 | Authorization |
| GET | `/api/v1/rag/kb/{kbId}/documents` | 文档列表 | Authorization |
| POST | `/api/v1/rag/kb/{kbId}/documents` | 上传文档（异步 202） | Authorization |
| DELETE | `/api/v1/rag/documents/{id}` | 删除文档 | Authorization |
| POST | `/api/v1/rag/search` | 向量检索测试 | Authorization |

#### 内部 RAG 接口 `/internal/rag`（chat-core 经 RagClient 调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/rag/search` | 知识库语义检索 |
| POST | `/internal/rag/embed` | 文本向量化 |
| POST | `/internal/rag/memory/save` | 保存对话记忆（短期+长期+画像） |
| POST | `/internal/rag/memory/context` | 构建记忆上下文 |
| POST | `/internal/rag/memory/facts/save` | 保存用户事实 |
| POST | `/internal/rag/memory/facts/recall` | 召回用户事实 |
| POST | `/internal/rag/invoke` | RAG 增强回答（非流式） |
| POST | `/internal/rag/invoke-stream` | RAG 增强回答（SSE 流式） |

#### 内部知识图谱接口 `/internal/graph`（chat-core 经 GraphClient 调用）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/internal/graph` | 获取图谱 |
| GET | `/internal/graph/search` | 搜索图谱 |
| GET | `/internal/graph/stats` | 图谱统计 |
| POST | `/internal/graph/import` | 批量导入 |
| GET | `/internal/graph/import/status` | 导入进度 |
| POST | `/internal/graph/extract` | 三元组抽取 |

---

### 0.3 chat-games（游戏服务，端口 8083）

#### 城堡围攻 `/api/v1/games/castlesiege/lords`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/v1/games/castlesiege/lords` | 领主排行榜 | 公开 |
| POST | `/api/v1/games/castlesiege/lords/sync` | 同步排行榜 | JWT |

#### SQL 执行台 `/api/v1/sql`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/sql/login` | SQL 台登录（5次失败锁15分钟） | 公开 |
| POST | `/api/v1/sql/execute` | 执行 SQL（限频 30次/分钟，禁多语句） | X-Admin-Token |

---

### 0.4 chat-media（多模态服务，端口 8084）

#### 媒体生成 `/api/v1/media`

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/v1/media/generate` | 生成（文生图/文生视频/图生3D） | JWT（用户级限流） |
| GET | `/api/v1/media/status/{id}` | 生成状态查询 | JWT |
| GET | `/api/v1/media/history?type=` | 生成历史 | JWT |
| GET | `/api/v1/media/3d-access` | 3D 生成权限检查 | JWT |

---

### 0.5 WebSocket `/ws/chat`

| 事件类型 | 方向 | 说明 |
|----------|------|------|
| `chunk` | S→C | 流式文本片段 `{type, req_id, seq, content}` |
| `done` | S→C | 流式完成 `{type, req_id, answer, metadata}` |
| `cache_hit` | S→C | 缓存命中 `{type, req_id, answer}` |
| `error` | S→C | 错误 `{type, req_id, code, message}` |
| `round_start` | S→C | 辩论轮次开始 `{type, round, model_ids}` |
| `round_response` | S→C | 辩论轮次响应 |
| `round_end` | S→C | 辩论轮次结束 |
| `synthesis` | S→C | 辩论汇总 |

---

### 0.6 API 统计

| 维度 | 数量 |
|------|------|
| chat-web 对外端点 | **52** |
| chat-llm 对外端点 | **20** |
| chat-llm 内部端点 | **15** |
| chat-games 端点 | **4** |
| chat-media 端点 | **4** |
| WebSocket 事件 | **8** |
| **合计** | **~103 个端点 + 8 个 WS 事件** |

---

## 1 高层概览
- 功能边界：
  - 管理模块：ModelConfig 管理（provider、model、apiKey、优先级、启用/禁用）
  - 用户模块：注册/登录（JWT），用户资料
  - 聊天模块：前端发问 -> 写入 MySQL(messages status=queued) -> 发布到 MQ -> 消费者检查 Redis 缓存 -> 命中直接返回；未命中调用大模型流式返回 -> 结果写 MySQL + 写 Redis + 推送到前端（WebSocket/SSE）
  - 附件：图片上传（对象存储或本地/DB），消息可引用图片
  - 缓存/历史：Redis 保存 question cache + user history list
  - 中间件：RabbitMQ / Kafka（示例以 RabbitMQ JSON 消息为主）

## 2 ER（主要表）
- Users
- Messages
- ModelConfigs
- Attachments

关系简述：Messages.user_id -> Users.id；Attachments.message_id -> Messages.id；ModelConfigs 存储各模型提供方配置信息（含加密的 API key）

## 3 MySQL DDL（兼容 MySQL 8+）
-- users
CREATE TABLE users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(100),
  role VARCHAR(32) DEFAULT 'user',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at TIMESTAMP NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- model_configs
CREATE TABLE model_configs (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(64) NOT NULL,
  model VARCHAR(128) NOT NULL,
  api_key_encrypted TEXT NOT NULL,
  meta JSON NULL,
  priority INT DEFAULT 100,
  enabled BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- messages
CREATE TABLE messages (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  req_id CHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  question TEXT NOT NULL,
  answer JSON NULL,
  status VARCHAR(32) NOT NULL,
  provider VARCHAR(64) NULL,
  model VARCHAR(128) NULL,
  metadata JSON NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
  UNIQUE KEY uq_messages_reqid (req_id),
  INDEX idx_user_created (user_id, created_at),
  INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- attachments
CREATE TABLE attachments (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NULL,
  uploaded_by BIGINT NOT NULL,
  storage_url VARCHAR(1024) NOT NULL,
  mime_type VARCHAR(128),
  filename VARCHAR(255),
  size BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

备注：可选将流式片段拆到 message_chunks 表以便回放或更细粒度持久化。

## 4 Redis 设计（键、类型、TTL、目的）
- Key patterns:
  - question:{sha256(question + model + provider)} -> JSON string (answer + metadata) TTL: 24h（可配置）
  - user:{uid}:history -> LIST of message IDs (LPUSH newest, LTRIM to N)
  - inflight:{req_id} -> STRING (consumer lock) TTL: 60s-300s
  - rate:{uid}:tokens -> COUNTER or 令牌桶结构
  - websocket:session:{sessionId} -> HASH {user_id, socket_id, last_seen}

建议：使用 Redis JSON 模块可提高可操作性；history 存 ID 以减小 Redis 大对象量，并从 MySQL 拉取完整内容。

## 5 MQ 消息格式（示例：RabbitMQ JSON）
Exchange: chat.requests
Routing key: chat.request

消息体示例：
{
  "req_id":"uuid-v4",
  "user_id":123,
  "question":"用户的原始问题文本",
  "attachments":[{"id":45,"url":"..."}],
  "preferred_model_config_id":7,
  "timestamp":1690680000,
  "metadata":{ "clientLang":"zh","trace_id":"..." }
}

消费者职责：获取 inflight 锁 -> 检查 Redis 缓存 -> 命中返回并更新 DB -> 未命中调用模型并流式返回 -> 写 Redis 与 DB -> 推送给前端。

## 6 OpenAPI 概要
Base: /api/v1
Security: bearerAuth (JWT)

Schemas（简要）:
- User { id, email, name, role }
- AuthRequest { email, password }
- AuthResponse { access_token, token_type, expires_in, user }
- MessageCreate { req_id, question, attachments?, preferred_model_config_id? }
- MessageDetail { id, req_id, user_id, question, answer, provider, model, status, created_at }

主要 Endpoints:
- POST /auth/register
- POST /auth/login
- GET /users/me
- GET /users/{id}/history
- POST /messages  (入库并推 MQ，返回 202)
- GET /messages/{id}
- POST /debate    (启动辩论：`{topic, rounds, model_count, mode}`；`model_count` 默认 3 限 3~6，从已配置 chat 模型随机组队，WS 推送 round_start/round_response/stream_token/synthesis 等事件)
- Admin: /admin/models CRUD

行为说明：POST /messages 校验后入库(status=queued)并 publish 到 MQ，返回 202 Accepted 与消息 id，前端通过 WS 接收流式更新或后续 GET 获取最终结果。

**统一错误响应（2026-08-13 起全站生效）**：`{"ok":false,"code":<HTTP状态码>,"error":"<错误信息>"}`
- `code` 枚举：400 参数错误 / 401 未认证 / 403 无权限 / 404 不存在 / 429 限流 / 500 服务异常
- 由 `GlobalExceptionHandler` 统一输出，业务异常、参数校验异常、限流异常同构；前端 `apiClient` 拦截 401 统一登出

## 7 WebSocket 设计（流式）
Endpoint: /ws/chat
Auth: JWT（query param 或 subprotocol）

Frame 示例（JSON）：
- Server -> Client
  - CHUNK: {"type":"chunk","req_id":"...","seq":1,"content":"文本片段","meta":{"partial":true}}
  - DONE: {"type":"done","req_id":"...","answer":"完整答案","metadata":{"provider":"openai","model":"gpt-4o"}}
  - CACHE_HIT: {"type":"cache_hit","req_id":"...","answer":"...","metadata":{}}
  - ERROR: {"type":"error","req_id":"...","code":500,"message":"..."}

流式策略：consumer 在接收模型流时，将每个片段转发为 CHUNK；最终发送 DONE 并写入 DB/Redis。

## 8 示例请求/响应（节选）
- 注册
POST /api/v1/auth/register
Body: {"email":"alice@example.com","password":"P@ssw0rd","name":"Alice"}
Response 201: {"access_token":"ey...","expires_in":3600,"user":{"id":1,"email":"alice@example.com"}}

- 创建消息
POST /api/v1/messages
Header: Authorization: Bearer <token>
Body: {"req_id":"a1b2...","question":"帮我写一段关于xxx的总结","preferred_model_config_id":3}
Response 202: {"id":123,"req_id":"a1b2...","status":"queued","ws_channel":"/ws/chat?token=..."}

- WebSocket 流
{ "type":"chunk","req_id":"a1b2...","seq":1,"content":"第一段内容..." }
{ "type":"done","req_id":"a1b2...","answer":"完整答案...","metadata":{"provider":"openai","model":"gpt-4o"} }

## 9 索引、性能与容量建议
- messages: UNIQUE(req_id), INDEX(user_id, created_at), INDEX(status)
- model_configs: INDEX(provider, model)
- users: UNIQUE(email)
- DB：视高并发做分表/分区；messages 可按时间分区
- Redis：设置合理 TTL，使用 LRU 策略；history 存 ID
- MQ：合理 consumer 并发、预取与 ack 策略

## 10 幂等、速率限制与抗刷
- 客户端必须生成 req_id（UUID v4）用于幂等，服务端用 UNIQUE(req_id) 防止重复处理
- Redis rate:{uid} 用作速率限制（令牌桶/计数）
- inflight:{req_id} 锁（SETNX）防止重复消费

## 11 安全与密钥管理
- api_key_encrypted：使用云 KMS 或应用 master key 加密储存，绝不明文
- admin 权限管理用于 CRUD model_configs
- HTTPS、JWT（短期 access + refresh）、操作审计
- 隐私声明：告知用户内容可能发送至第三方模型并允许用户选择

## 12 可观测性与监控
- 关键指标：MQ backlog、消费者延时、Redis 命中率、模型调用失败率、每用户 QPS
- 落地（2026-08-13）：业务级指标（意图漏斗命中/耗时、Multi-Agent 工作流启动/收敛）由 `CoreBusinessMetricsAspect` AOP 切面横切采集入 Prometheus（`core.intent.funnel.*` / `core.agent.workflow.*`），业务类零侵入
- 日志：结构化（JSON），trace_id 贯穿 REST->MQ->consumer->provider
- 报警：MQ backlog 长、Redis 内存高、错误率上升 + 4 条业务告警（漏斗命中率/工作流降级率/收敛失败/LLM token 激增）

## 13 容错与运维策略
- 消费者实现重试与死信队列(DLQ)
- 模型调用超时/取消策略
- 当模型服务不可用时返回友好错误并入 DLQ
- 定期清理与历史归档

## 14 可扩展功能（未来）
- 多租户 model_configs
- 模型 A/B 测试、优先级路由
- 异步通知（邮件/Slack）

## 15 开发优先级（MVP）
1. 用户注册/登录（JWT）+ users 表
2. POST /messages 入库(status queued) + publish to MQ
3. 简单 consumer：检查 Redis、调用模型（或模拟）、通过 WebSocket 推流 CHUNK/DONE
4. model_configs CRUD（加密 api_key）
5. Redis question cache + user history list
6. 图片上传（attachments 表）

---

---

## 9. 知识库管理 (RAG)

> 需要 `admin` 角色，通过 `chat-web` (KnowledgeBaseController) 代理转发到 **`chat-llm`** (KnowledgeController)，RAG 运行时已迁至 chat-llm（2026-08）
> 内部调用需要 `User-Agent: chat-web` 请求头（防止被 `IpRateLimitInterceptor` 拦截）
> 需 `chat-llm` 开启 `app.rag.enabled=true` 才注册 `/api/v1/rag/*` 路由；chat-core 内部经 `RagClient` 调 `/internal/rag/*`（知识检索/向量化/记忆/RAG 回答）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/rag/kb` | 知识库列表 |
| POST | `/api/v1/rag/kb` | 创建知识库 |
| DELETE | `/api/v1/rag/kb/{id}` | 删除知识库 |
| GET | `/api/v1/rag/kb/{id}/documents` | 文档列表 |
| POST | `/api/v1/rag/kb/{id}/documents` | 上传文档 (multipart `file`) |
| DELETE | `/api/v1/rag/documents/{docId}` | 删除文档 |

### 创建知识库

```
POST /api/v1/rag/kb
Content-Type: application/json
Authorization: Bearer {token}

{
    "name": "通用知识库",
    "description": "系统默认知识库"
}
```

### 上传文档

```
POST /api/v1/rag/kb/{id}/documents
Content-Type: multipart/form-data
Authorization: Bearer {token}

file: document.pdf
```

---

## 16. 知识图谱 API

> 数据来源 Neo4j 图数据库。前端通过 `chat-web` (GraphController) 代理转发到 `chat-core` (InternalApiController)。
> 内部调用需要 `User-Agent: chat-web` 请求头。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/graph` | 获取知识图谱节点和关系边 |
| GET | `/api/v1/graph/search` | 按关键词搜索实体及关联图 |
| GET | `/api/v1/graph/stats` | 获取图谱统计（实体数、关系数） |

### 获取知识图谱

```
GET /api/v1/graph?limit=100&minEntityWeight=1&minRelationWeight=1

参数：
  limit            — 返回节点数上限，默认 100
  minEntityWeight  — 实体最低权重（关系数），≤ 此值的实体不显示，留空默认 1
  minRelationWeight — 关系最低权重（累计次数），≤ 此值的关系线不显示，留空默认 1

响应：
{
  "nodes": [{ "id": 123, "label": "深度学习", "value": 15 }],
  "edges": [{ "source": 123, "target": 456, "label": "子领域", "weight": 5, "question": "..." }]
}
```

- 实体权重 = 该实体关联的 RELATION 边总数，值越大越核心
- 关系权重 = Neo4j 中同一条关系累积出现的次数 (`r.count`)，每次抽取相同三元组时 +1
- `minEntityWeight` 和 `minRelationWeight` 均默认 1（不过滤），用户可手动设置筛选核心节点

### 搜索知识图谱

```
GET /api/v1/graph/search?keyword=深度&limit=30&minEntityWeight=1&minRelationWeight=1

参数：
  keyword          — 搜索关键词（模糊匹配实体名称）
  limit            — 返回结果数上限，默认 30
  minEntityWeight  — 同获取接口
  minRelationWeight — 同获取接口

响应：格式同上
```

### 图谱统计

```
GET /api/v1/graph/stats

响应：
{ "entityCount": 250, "relationCount": 480 }
```

### 知识抽取流程

1. AI 对话完成后，`ChatProcessor` / `DebateProcessor` 异步调用 `KnowledgeGraphService.extractAndSaveAsync()`
2. AI 从对话文本中提取三元组（subject → relation → object）
3. 写入 Neo4j：`MERGE` 实体和关系，`ON CREATE SET r.count = 1`，`ON MATCH SET r.count += 1`
4. 重复出现的同一三元组自动累加关系权重

---

如需将上述内容拆分为单独文件（OpenAPI YAML、SQL DDL 单文件、架构图），或需要将 OpenAPI 写为完整 YAML 并提交到仓库，请回复说明目标文件名与路径（默认放在 docs/）。
