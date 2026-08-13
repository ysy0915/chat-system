# V1.0 系统 — ER 与 API 设计文档

本文档包含 MySQL DDL、OpenAPI 概要、Redis 键设计、MQ 消息格式、WebSocket 流示例、示例请求/响应、索引与运维/安全建议。可直接用于实现或生成开发任务。

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
- 日志：结构化（JSON），trace_id 贯穿 REST->MQ->consumer->provider
- 报警：MQ backlog 长、Redis 内存高、错误率上升

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
