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
├── RAG 知识库 (异步上传 + 向量化)
├── 知识图谱 (Neo4j, 60s 自动重连)
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
chat-llm  (:9095)    — 独立 LLM 服务 (REST + gRPC :9195)
frontend             — 可拖拽树状辩论画布 + 思考链渲染
```
