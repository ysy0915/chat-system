# 3.0 版本更新公告

> 发布日期：2026-08-11

---

## 一、树状辩论模式

全新的多维分析辩论：将复杂问题拆解为多个视角，多模型并行辩论后汇总综合结论。

```
用户提问 → LLM 语义拆解 → 2~3 个分析视角
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
            视角A           视角B           视角C
         (豆包×DeepSeek×千问 3轮辩论, LangGraph 编排)
              │               │               │
              ▼               ▼               ▼
           视角结论        视角结论        视角结论
              └───────────────┼───────────────┘
                              ▼
                        LLM 综合汇总
```

### 特性

- **智能拆解**：LLM 按问题语义自动生成 2~3 个分析角度，异常时回退默认视角
- **多方辩论**：每视角豆包（正方）× DeepSeek（反方）× 千问（中立）3 轮交锋
- **LangGraph 混合编排**：视角内用 `StateGraph` 做图式循环编排，视角间用 Java 并行调度
- **可拖拽画布**：暗色主题 DAG 树图，支持鼠标拖拽 / 滚轮缩放 / 双指缩放
- **逐句换行**：最终结论的理由按句自动分割展示，阅读更清晰
- **容错完备**：单视角失败不影响其他视角，汇总 LLM 失败自动本地拼接

### 入口

辩论页底部标签切换：「线性博弈」↔「树状博弈」

---

## 二、意图识别三层漏斗

告别参数硬编码——系统自动识别用户意图并匹配合适的 LLM 策略。

```text
L1 规则匹配 (0~1ms)   → 接住 5~15%  明确命令 ("翻译" / "写代码")
L2 语义匹配 (30~80ms) → 接住 70~85%  常规对话 (Embedding + k-NN)
L3 LLM 分类  (200~1000ms) → 兜底 10%  模糊意图 → UNKNOWN
```

- 10 种意图分类：闲聊、知识问答、代码生成、创意写作、逻辑推理、摘要、情绪支持、任务执行、翻译
- **意图驱动 Temperature 路由**：代码 0.2 → 创意 0.95，自动调优
- 规则热加载 + 自动挖掘（TF-IDF 高频词 → LLM 标注意图）
- 每层失败自动降级，不阻塞主流程

---

## 三、思考链实时展示

LLM 的推理过程不再隐藏在 "思考中…" 之后。

- 流式输出中自动检测 `<thinking>...</thinking>` 标签
- 推理内容以灰色斜体小字实时展示在 AI 气泡顶部
- 仅实时展示，不存入数据库
- 智能触发：复杂意图自动开启，简单对话跳过
- 安全降级：300 字符无标签自动切非思考模式

---

## 四、多层安全防护

### 限流体系

| 层级 | 限制 | 说明 |
|------|------|------|
| IP 全局限流 | 600 次/分钟 | 单 IP 全栈请求 |
| 用户级限流 | 20 次/分钟 / 200 次/小时 | Redis 滑动窗口 |
| 敏感接口限流 | 10 次/分钟 | 登录 / 注册独立限制 |
| 自动拉黑 | 1000 次 / 60s 触发 | 封禁 10 分钟 |

### 其他防护

- **UA 过滤**：拦截 curl / wget / scrapy 等爬虫，放行搜索引擎
- **CORS 白名单**：`localhost:*` / 生产域名 / `yangsy.online`，加 CSP 头防 XSS
- **IP 管理 API**：查看黑名单、手动拉黑 / 解封、查看请求统计（`X-Admin-Password` 鉴权）
- **Fail-open 容错**：Redis 异常时放行，不阻塞正常服务

---

## 五、AI 错误自愈

LLM 调用出错了不再直接失败，系统会按错误类型自动尝试恢复：

| 错误 | 自愈策略 |
|------|---------|
| 频率限制 / 超时 | 换同 Provider 其他模型重试 |
| 认证失败 | 跳过失败模型，用默认模型重试 |
| 网络错误 | 等待 1 秒后重试 |
| 解析错误 | 降 temperature 到 0.3 重试 |
| 模型不存在 | 不重试，直接报错 |

---

## 六、LLM 调用升级

### Fluent Builder API

```java
llmClient.forConfig(modelConfig)
    .scene("chat")          // 场景预设
    .temperature(0.7)       // 可选覆盖
    .stream()               // 流式输出
    .execute(messages);     // 执行
```

- 场景化调用：`chat` / `debate` / `treehole` / `summary` 等
- Nacos 动态配置：修改 temperature / maxTokens / persona 无需重启

### 策略模式 + 熔断

- OpenAI 兼容策略（千问 / DeepSeek） + 豆包专用策略
- Resilience4j CircuitBreaker：50% 失败率 → 30s 熔断
- 自动重试最多 3 次（间隔 500ms），单次 30s 超时

---

## 七、无状态化与水平扩展

chat-web 现在支持多实例部署，随时扩容：

- WebSocket Session 从本地内存迁移到 Redis（`ws:session:page` Hash）
- 15 分钟空闲超时自动清理
- 就绪探针 `/actuator/health/readiness` 验证 Redis 状态
- 所有服务无状态可水平扩展

---

## 八、500 并发性能优化

全面调优后，500 并发 AI 模式下零失败。

| 组件 | 优化前 | 优化后 |
|------|--------|--------|
| HikariCP (web) | 5 | 30 |
| Tomcat threads (web) | 50 | 200 |
| Tomcat connections (web) | 500 | 2000 |
| HikariCP (core) | 10 | 30 |
| Tomcat threads (core) | 100 | 200 |
| Nginx worker_connections | 1024 | 4096 |
| Nginx keepalive | 无 | 64 |
| Nginx proxy_read_timeout | 60s | 120s |

**效果：** 500 并发 P50 从 4.3s 降至 154ms，成功率 99.8% → 100%。

---

## 九、其他改进

- 前端缩放按钮响应提速：DOM 直接操作 + rAF 节流，连续点击无延迟
- 移动端适配：`touch-action: none` + `100dvh` 固定高度，支持双指缩放
- 监控面板：Prometheus + Grafana + Zipkin 链路追踪
- 部署运维手册：新增树状辩论架构 / LangGraph 混合模式 / 排障指南

---

## 十、2026-08-12 安全与稳定性加固

### 安全加固 (P0)

| 项目 | 内容 |
|------|------|
| JWT 弱密钥校验 | `jwt.secret` 少于 32 字节直接拒绝启动，`openssl rand -base64 48` 生成 |
| 登录/注册 DTO 校验 | `@Valid` + `@Size` 统一参数校验，替代手写长度判断 |
| 上传大小限制 | 全模块统一 10MB（`max-file-size` / `max-request-size`） |
| SQL 执行台加固 | 登录失败 ≥5 次锁 15 分钟；执行频率每分钟 30 次；新增 `OUTFILE`/`SLEEP` 等危险关键字；禁多语句 |
| 媒体生成限流 | 用户级限流（`RateLimitService`），超限 429 + `retry_after` |

### 性能与稳定性 (P1)

| 项目 | 内容 |
|------|------|
| RAG 上传异步化 | 文档上传立即返回 202 + `status=processing`，解析/分片/向量化后台执行 |
| 全文索引 SQL | `docs/sql/fulltext_search_indexes.sql` 解决消息 `LIKE '%kw%'` 全表扫描 |
| AuthUtils 收敛 | 公共认证工具类，消除各 Controller 重复的 `extractUserId` |
| Neo4j 自动重连 | `kg-reconnect` 守护线程每 60s 重试，修复启动竞态导致知识脉络图数据丢失 |

### 运维与测试 (P1/P2)

| 项目 | 内容 |
|------|------|
| Prometheus 告警 | `prometheus-alert-rules.yml`：宕机/高延迟/高内存/高错误率，Alertmanager 接线 |
| chat-llm 容器化 | `Dockerfile-llm` + docker-compose 增加 chat-llm (profiles: ["all"]) |
| chat-web 测试 | 12 个 Controller 全覆盖（54 个测试），含代理层 `KnowledgeBaseControllerTest` |
| 前端测试 | hooks vitest 测试（useAuthUser / useAutoScroll） |
| 树状博弈展示 | 最终结论去掉「【最终结论】」标签，逐句空行展示 |

---

## 模块分工

```
chat-core (:9090)
├── LLM 策略工厂 + LLMClient + ModelRouter
├── 意图识别三层漏斗 (L1 规则 / L2 语义 / L3 LLM)
├── 思考链展示 (ThinkingStreamParser 状态机)
├── 标准辩论 (LangGraph4j StateGraph)
├── 树状辩论 (Java 上层 + LangGraph 子图混合编排)
├── RagClient (跨进程调 chat-llm /internal/rag/*)
├── 知识图谱 (Neo4j, 60s 自动重连) — 运行时仍在本模块
└── AI 错误自愈 + 熔断降级

chat-web (:8081)
├── REST API + WebSocket 接入
├── JWT 认证 + 弱密钥校验 + 多层 IP 限流 + 自动拉黑
├── DTO 参数校验 (@Valid)
├── CORS 白名单 + CSP 头
├── IP 管理后台 API
└── 无状态 Session (Redis)

chat-games (:8083)   — 游戏服务 + SQL 执行台 (登录锁定 + 频率限流)
chat-media (:8084)   — 多模态生成 (用户级限流)
chat-llm  (:9095)    — 独立 LLM 服务 + RAG 运行时 (REST + gRPC :9195)
frontend             — 可拖拽树状辩论画布 + 思考链渲染
```

---

## 九、RAG / 知识图谱运行时迁移至 chat-llm（2026-08-12）

### 变更内容

| 能力 | 迁移前 | 迁移后 |
|------|--------|--------|
| RAG 运行时 | chat-core（`rag` 包：知识库 CRUD、向量检索、对话记忆、RAG 回答） | **chat-llm**（`rag/legacy` 包 + 新版多数据源 `rag` 包），chat-core 删除旧 `rag` 包 |
| chat-core 调用 | 本地 `VectorStoreService` / `ConversationMemoryService` | `RagClient`（Java 11 HttpClient）跨进程调 `/internal/rag/*` |
| chat-web 知识库代理 | `CoreClient` → chat-core | `CoreClient` → chat-llm `/api/v1/rag/*`（`app.llm-service.base-url`） |
| 知识图谱 | chat-core（`KnowledgeGraphService` 等 4 服务直连 Neo4j） | **chat-llm**（`KnowledgeGraphService` / `TripleExtractionService` / `GraphRepositoryService` / `BatchImportService`，`app.knowledge-graph.enabled` 开关），chat-core 删除本地实现并移除 Neo4j 依赖，改 `GraphClient` 跨进程调 `/internal/graph/*` |

### 架构要点

- **两套 RAG 并存**：新版多数据源 RAG（`rag.enabled`，`EmbeddingService` 默认 1536 维）与 legacy kbId 模型（`app.rag.enabled`，`LegacyEmbeddingService` 1024 维）同驻 chat-llm，配置开关独立
- **维度隔离**：旧知识库 / 意图匹配 Milvus 集合为 1024 维，与新版 1536 维不兼容，故 `LegacyEmbeddingService`（知识库）与 `IntentEmbeddingService`（意图 L2）独立实现
- **chat-llm bean 注册**：`LlmApplication` 通过 `@MapperScan` 扫描 chat-common `repository` 包与 `rag.legacy` 包；`@Import` 显式注册 `LlmConfigProperties` / `DirectLLMClient` / `BaseUrlResolver` / `JwtUtil`（均位于 `com.example.chat.*` 默认扫描路径外）
- **条件注册**：`/internal/rag/*` 与 `/api/v1/rag/*` 需 `app.rag.enabled=true`，新版 `/api/v1/llm/rag/*` 需 `rag.enabled=true`

### 部署要点

- `.env` 需配置：`RAG_ENABLED=true`、`LLM_SERVICE_BASE_URL=http://127.0.0.1:9095`、`NEO4J_PASSWORD`、`EMBEDDING_*`
- 重启顺序：llm → core → web（chat-core 启动时经 `RagClient` 探测 chat-llm）
- 验证：`/internal/rag/embed` 200、`/api/v1/rag/kb` 401（未登录拦截）、`/api/v1/llm/rag/datasources` 200

---

## 十一、双 core 高可用 + Prometheus 生产监控（2026-08-12）

### 双 core 部署

| 项目 | 内容 |
|------|------|
| 实例布局 | core 主实例 :9090（Xmx768m，autoChat 定时任务仅此执行）+ 从实例 :9092（Xmx512m）；9091 被 milvus-standalone 占用不可用 |
| web 轮询 | `--app.core.base-urls=http://127.0.0.1:9090,http://127.0.0.1:9092`，`CoreClient` 解析逗号列表轮询 |
| stop 广播 | chatStop / treeHoleStop 改用 `CoreClient.broadcast()` 广播到所有 core，任一成功即成功，根治"停止打在非承载实例上" |
| nodeId 固定 | `CrossNodeConfig` nodeId 固定为 `node-{port}`，重启不再生成带 UUID 新队列 → RabbitMQ 队列堆积根除 |
| 精确重启 | `restart-core.sh [9090\|9092\|all]` 按 `server.port` 精确 pkill；web 双实例 8081/8082 |

### Session 失忆修复（"回答完就断开"）

- 根因：`ChatHistoryBuilder.buildGroup` 不加载历史对话 → 多轮会话跨实例后上下文丢失
- 修复：`buildGroup` 复用 `buildFromRecent` 加载近期消息，个人对话/树洞恢复上下文记忆（commit `aa2aa14`）

### Prometheus 生产监控栈

| 项目 | 内容 |
|------|------|
| 部署形态 | 全部 `--network host` 容器（restart=always）：Prometheus:9094 · Alertmanager:9093 · node-exporter:9100 · blackbox-exporter:9115 · alert-webhook:9950 |
| 启动参数坑 | Prometheus 需 `--web.listen-address=:9094`（9090 被 core 占）；Alertmanager 需 `--cluster.listen-address=`（否则抢占 9094）；prometheus-data 需 `chown 65534:65534` |
| 告警链路 | 6 条规则（服务宕机/宿主机内存<8%/磁盘<15%/JVM堆>85%/CPU/负载）→ Alertmanager → alert-webhook.py → 钉钉（`DINGTALK_WEBHOOK`） |
| webhook 守护 | `setsid nohup` 启动 + `health-check.sh` 每分钟拉起，日志 `/opt/app/logs/prometheus-alerts.log` |
| micrometer 版本坑 | 必须 `micrometer-registry-prometheus 1.11.6`（与 Boot 3.1.6 匹配）；1.13.0 新版模型导致 `PrometheusMeterRegistry` 初始化失败、`/actuator/prometheus` 404 |
| 指标端点 | 4 个 Java 服务（core×2 + web×2）`/actuator/prometheus` 全部 200 |

---

## 十二、模型抽象升级：SPI 策略工厂（2026-08-12）

### 变更内容

模型接入从"注册中心硬编码分支"升级为 **SPI 插件化策略工厂**，新增厂商协议零改动注册中心：

| 类 | 职责 |
|----|------|
| `LLMProviderFactory`（新增） | SPI 扩展点接口：`type()` 与配置 `llm.providers[].type` 对应 + `create(ProviderConfig, ObjectMapper)` |
| `RestProviderFactory`（新增） | 内置 `rest` 类型 → 构造 `OpenAICompatProvider`（OpenAI 兼容 REST） |
| `SdkProviderFactory`（新增） | 内置 `sdk` 类型 → 构造 `OpenAISdkProvider`（OpenAI Java SDK） |
| `LLMProviderStrategyFactory`（新增） | 组合工厂：Spring Bean 自动收集 `LLMProviderFactory`、代码动态注册、未知类型回退 `rest` 不中断路由、`supportedTypes()` 供管理面展示 |
| `LLMProviderRegistry`（改造） | `init()` 删除 `if (pc.isSdk())` 硬编码分支，策略创建统一走 `strategyFactory.create(pc)` |

### 新增厂商接入方式（SPI）

```java
@Component
public class AnthropicProviderFactory implements LLMProviderFactory {
    @Override public String type() { return "anthropic-native"; }
    @Override public LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper) {
        return new AnthropicProvider(config, mapper); // 实现 LLMProviderStrategy
    }
}
```

配置 `type: anthropic-native` 即可被 Spring 自动收集生效，无需改动 `LLMProviderRegistry`。

### 验证

- 新增 `LLMProviderStrategyFactoryTest`（9 个用例）：内置 rest/sdk、SPI 收集、动态注册、非法注册忽略、未知类型回退
- 既有 `LLMProviderRegistryTest` / `OpenAICompatProviderTest` 全通过（便捷构造器兼容）
- Checkstyle 0 违规
- 生产双实例（9095/9096）已部署，`/actuator/health` 均 200，策略工厂注册日志正常

> ⚠️ 部署踩坑：`LLMProviderStrategyFactory` 有 Spring 主构造器 + 便捷构造器两个构造器，**Spring 构造器必须标 `@Autowired`**，否则 Spring 6 多构造器场景报 `No default constructor found`（单元测试直接 `new` 不会暴露）。

---

## 十三、模型自助管理面（2026-08-12）

### 背景

策略工厂落地后，新增模型/厂商已可从代码层面零改动接入（SPI），但运营侧仍需开发/运维手工改 YAML 或调内部 API。本次补齐**模型管理面**：DB 持久化 + 动态注册 API + 前端管控界面。

### 数据模型（表结构见 `docs/sql/llm_routing_schema.sql`）

| 表 | 用途 |
|----|------|
| `llm_provider_config` | 提供商（name/base_url/auth_type/invoke_type/enabled/is_default/priority） |
| `llm_provider_props` | 提供商 KV（`api_key` SECRET / `path` STRING），密钥与代码分离 |
| `llm_model_config` | 模型（model_name/display_name/model_type/max_tokens/enabled/is_default/priority） |

来源模型：**YAML 兜底 + DB 覆盖**。应用就绪（`ApplicationReadyEvent`）后从 DB 加载 enabled 且含 api_key 的提供商注册进 `LLMProviderRegistry`，覆盖同名 YAML 项。

### 新增文件

| 文件（chat-llm） | 职责 |
|----|------|
| `llm/routing/db/LlmProviderRow.java` / `LlmModelRow.java` | 三表行实体 |
| `llm/routing/db/LlmRoutingRepository.java` | MyBatis `@Mapper` 注解式 CRUD（provider/props/models） |
| `llm/routing/db/LlmProviderAdminService.java` | 核心：DB 加载/写库/注册中心同步；`refreshRegistry` 保证 enabled+key 才注册；apiKey 脱敏 |
| `llm/routing/db/LlmProviderAdminController.java` | `/api/v1/llm/admin/providers`：GET 列表 / GET types / POST / PUT /{id} / DELETE /{id} / POST reload |
| chat-web `LlmAdminProxyController.java` | 前端代理透传（前端不可直达 chat-llm），`CoreClient` 增加 6 个转发方法 |
| frontend `pages/AdminModels.jsx`（重写） | 静态卡片 → 动态管理页：提供商卡片（DB/YAML 徽标 + key 脱敏 + 模型列表）、新增/编辑弹窗（类型下拉来自 `supportedTypes()`、模型动态行）、删除确认、全量重载 |

### API 一览

```
GET    /api/v1/llm/admin/providers          # 列表（apiKey 脱敏，source: db/yaml）
GET    /api/v1/llm/admin/providers/types    # 策略工厂 supportedTypes
POST   /api/v1/llm/admin/providers          # 新增（重复名校验）
PUT    /api/v1/llm/admin/providers/{id}     # 更新（apiKey 空串 = 保留原值）
DELETE /api/v1/llm/admin/providers/{id}     # 删除（级联 props/models + 路由卸载）
POST   /api/v1/llm/admin/providers/reload   # 全量重载（reset → YAML → DB）
```

### 安全设计

- apiKey 存 `llm_provider_props`（SECRET 类型），列表/详情接口**完全不返回 apiKey（含脱敏片段）**，仅返回 `hasApiKey` 布尔状态
- 编辑时 apiKey 留空表示不修改（不会回传明文）
- 非法参数统一 400 返回（`IllegalArgumentException` 处理器）

### 管理员权限与 Key 保护（追加，同日）

- **chat-web 代理层鉴权**：写操作（POST/PUT/DELETE/reload）必须携带 `X-Admin-Pass` 请求头（与监控面板同源 `monitor.password`），校验失败 403；新增 `POST /api/v1/llm/admin/login` 供前端解锁验证；只读列表放行
- **chat-llm 纵深防御**：配置 `app.llm.admin-password` 后，绕过 chat-web 直连 chat-llm 的写操作同样被拦截
- **前端**：模型管理页默认只读（可查看脱敏列表），「新增/编辑/删除/重载」按钮需管理员密码解锁（sessionStorage 标记，与监控面板一致）；页面提示"apiKey 仅保存不展示"
- **网关放行**：`SecurityConfig` 将 `/api/v1/llm/admin/**` 加入 `permitAll`（写操作自有 `X-Admin-Pass` 鉴权）；`ViewConfig` 的 `IpRateLimitInterceptor` 排除该路径，避免 IP 限流误拦截（双白名单缺一不可）

### 验证

- `chat-llm` 全量 74 测试通过（管理面新增代码编译通过）
- `chat-web` 编译通过，转发链路（前端 → web 代理 → chat-llm）就绪
- 前端构建成功（AdminModels 动态页）
- 双 llm 实例（9095/9096）注意事项：写库即时同步本实例；另一实例通过 `/reload` 或重启生效（DB 为权威，重启自动加载）

### 部署验证（同日，生产）

- 生产链路实测：GET 列表 200（脱敏，仅 `hasApiKey`）、`POST /login` 成功、无密码写操作 403、带 `X-Admin-Pass` 创建 200（含模型行）→ 删除 200
- 修复：`LlmProviderAdminService.toRow()` 给 `invokeType` 补默认值 `"rest"`（MyBatis 显式插入 NULL 会绕过 DB `DEFAULT 'rest'`，导致 `Column 'invoke_type' cannot be null` 500）
- 双实例/双 web 经 `llm-lb`（nginx 8095 → 9095/9096 轮询）转发；写操作后另一实例内存路由残留可通过 `/reload` 收敛（DB 为权威）
- 主服务器 Nginx 外部链路：SPA `/chat/admin/models` 200、`/api/v1/llm/admin/providers` 200/403 均正确

---

## 十四、辩论场次可选（追加）

观点辩论场（线性博弈）不再固定 3 轮，支持用户自选场次。

### 特性

- **场次选择**：输入框上方新增「场次」选择器，可选 1/2/3/4/5 轮，默认 3 轮（树状博弈模式不显示，保持内部固定轮数）
- **动态进度条**：顶部进度条按所选场次渲染「第 N 轮」节点
- **动态提示词**：整合提示词随轮数动态生成（"综合 N 轮辩论内容"）
- **防滥用**：后端统一校验轮数范围 1~10（前端仅开放 1~5），非法值回退默认 3

### 实现

| 层 | 改动 |
|----|------|
| 前端 `Debate.jsx` | `roundCount` state（默认 3）+ 场次选择器（线性模式显示，辩论中禁用）+ 进度条/欢迎语动态化 + 请求体带 `rounds` |
| 前端 `debate.css` | `.debate-rounds-picker` / `.debate-rounds-btn` 场次选择器样式 |
| chat-core `DebateProcessor` | `parseRounds()` 解析（默认 3，限 1~10）；`runDebate` 循环 `1..totalRounds`；`buildSynthesisPrompt` 轮数文案动态化 |
| chat-web `DebateController` | 透传 `rounds`（同样限 1~10），经 `coreClient.debateStart` 转发 |

### 追加修复（同日，生产实测后）

**LangGraph4j 分支轮数不生效**：生产 `.env` 未设置 `LANGGRAPH4J_DEBATE_ENABLED`，jar 内默认 `true` → 辩论走 `DebateGraphService` 图分支，`maxRounds` 固定为配置 `app.langgraph4j.debate.rounds:3`，前端选择的轮数被忽略。修复：

- `DebateGraphService.execute` 新增重载 `execute(reqId, userId, topic, rounds)`（限 1~10），`buildGraph` 增加 `maxRounds` 参数（`maxSteps`、`state.maxRounds` 随之动态化）；旧签名委托新实现
- `DebateProcessor.runLangGraph4jDebate` 透传 `totalRounds` 到图服务

**模型名显示兜底**：前端思考卡片依赖 `start` 事件里的 `models`（id→name），若事件缺失会回退显示"模型1/2/3"。新增 `DEFAULT_MODEL_NAMES = {1:'豆包', 2:'千问', 3:'DeepSeek', 4:'千问'}` 兜底，任何情况下均显示真实模型名（预览区/思考卡片/响应卡片统一）。

### 验证

- chat-core 全量 154 测试通过（含 DebateTreeProcessor / LLMInvoker 等）
- 后端编译、前端构建成功
- 生产部署：core 9090/9092 + web 8081/8082 全部重启健康（HTTP 200），主服务器 SPA `/chat/debate` 200
- 生产 jar 内 `DebateGraphService` 已含 `effectiveRounds` 逻辑（python3 校验 class 字节码）
