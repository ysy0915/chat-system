# LLM 策略与路由说明

> 说明多 LLM 模型的调用策略、路由机制、已知问题与修复方案

---

## 一、架构概览

```
ChatProcessor / DebateProcessor (业务层)
    │
    ▼
LLMInvoker (统一调用入口)
    │
    ├── ModelRouter (关键词智能路由)
    │       └── 根据任务类型选择模型
    │
    ▼
LlmBundleClient (统一多模型调用)
    │
    ├── DirectLLMClient          →  降级 HTTP 直连 (chat-core 内)
    └── chat-llm 独立服务 (:9095)
            ├── LLMProviderFactory (SPI) + LLMProviderStrategyFactory
            │       按 type 查字典创建策略 (Spring 自动收集 / 动态注册 / 回退 rest)
            ├── OpenAICompatProvider →  千问 (Qwen) / DeepSeek / 豆包 (REST)
            ├── OpenAISdkProvider    →  OpenAI SDK (type: sdk)
            └── LLMProviderRegistry  →  多 Provider 注册与路由
```

---

## 二、LLM 策略

> 旧的 chat-core 策略层（`LLMStrategy` 接口 / `LLMStrategyFactory` / `OpenAICompatStrategy` / `DoubaoStrategy`）已于 2026-08 清理废弃，统一迁移至 **chat-llm 独立服务** 的多 Provider 架构。

### 2.1 `chat-llm` Provider 架构 (chat-llm 模块)

`LLMProviderRegistry` 按配置注册 Provider，统一对外提供同步/流式调用：

| Provider | 类型 | 适用模型 |
|---------|------|---------|
| `OpenAICompatProvider` | REST (`type: rest`) | 千问 (Qwen)、DeepSeek、豆包（OpenAI 兼容端点） |
| `OpenAISdkProvider` | SDK (`type: sdk`) | OpenAI、Azure OpenAI 等官方 SDK 支持 |

**策略工厂（2026-08-12 落地，SPI 插件化）**：
- `LLMProviderFactory`：SPI 扩展点，`type()` 与配置 `llm.providers[].type` 对应
- `LLMProviderStrategyFactory`：组合工厂，Spring Bean 自动收集所有 `LLMProviderFactory`、支持代码动态注册、未知 `type` 回退 `rest` 不中断路由、`supportedTypes()` 供管理面展示
- `LLMProviderRegistry.init()` 策略创建统一走 `strategyFactory.create(pc)`，不再按 `isSdk()` 硬编码分支
- 新增厂商 = 实现 `LLMProviderFactory` + `LLMProviderStrategy` 并标注 `@Component`，零改动注册中心

**OpenAI 兼容 REST 调用特点**：
- 使用 `java.net.http.HttpClient` 直接构建 HTTP 请求
- 通过配置的 `apiKey`、`baseUrl` 动态绑定
- 支持流式 (SSE) 与非流式两种模式
- **重要**：**不设置** `Accept-Encoding: gzip`，因为 `BodyHandlers.ofString()` 不会自动解压

### 2.2 `GraphExecuteService` — 自研图执行引擎 (chat-llm)

不依赖第三方 LangGraph 框架，自研轻量图引擎：
- 逻辑节点（`nodeType=logic`）— 执行 `compare / increment` 表达式
- 并行分支（`branches`）— 一个节点内多个 LLM 并行调用，各自流式回调
- 状态写入（`sink / sinkAppend`）— 节点输出写入指定 state 键
- 重试自愈（`retryCount / fallbackNodeId`）
- 流式事件（`GraphStreamEvent`）— 按 nodeId/branchId 标识

### 2.3 `DirectLLMClient` (chat-core)

**用途**：直接 HTTP 调用的通用客户端（备用/降级方案）

**特点**：
- 不依赖 Provider 注册，直接构建 HTTP 请求
- 用于 `LLMInvoker` 不可用时（未注入 / 熔断）的降级调用
- 统一了 KnowledgeGraphService / ModelAutoChatService 中的重复降级 HTTP 逻辑

---

## 三、意图识别驱动 LLM 路由

### 3.1 三层漏斗（IntentFunnelEngine）

意图识别不是简单的 LLM 分类，而是**三层漏斗**架构，越上层越快、越下层越聪明：

| 层 | 引擎 | 速度 | 命中率 | 处理什么 |
|---|------|------|--------|---------|
| L1 | RuleBasedMatcher | 0-1ms | 5-15% | 固定的明确命令 (Trie + Regex) |
| L2 | ContextMatcher | 30-80ms | 70-85% | 常规意图 (Embedding + Milvus) |
| L3 | ToolIntentMatcher | 200-1000ms | <10% | 复杂语义 + 工具执行 (LLM + MCP) |

### 3.2 意图 → LLM Temperature

识别出意图后，`IntentRoutingHelper` 动态调整主模型参数：

| 意图 | Temperature | 原因 |
|------|------------|------|
| CODE_GENERATION | 0.2 | 代码需稳定输出 |
| REASONING | 0.2 | 逻辑推理需一致性 |
| SUMMARIZATION | 0.1 | 摘要需精确 |
| TRANSLATION | 0.1 | 翻译需精确 |
| KNOWLEDGE_QA | 0.3 | 问答需准确 |
| TASK_EXECUTION | 0.3 | 任务需可控 |
| GENERAL_CHAT | 0.7 | 闲聊自然 |
| EMOTIONAL_SUPPORT | 0.85 | 情感需多样 |
| CREATIVE_WRITING | 0.95 | 创作需想象力 |

### 3.3 关系：意图识别 vs 模型路由

- **意图识别**（IntentFunnelEngine）：判断用户**想做什么**
- **模型路由**（ModelRouter）：判断**用哪个模型**去完成

两者独立工作：先识别意图 → 根据意图调 temperature → 再按任务类型路由模型。

## 四、模型路由

### 4.1 `ModelRouter` (关键词智能路由)

根据用户输入的关键词和场景规则，自动选择合适的 LLM 模型。

路由规则：
| 任务类型 | 关键词 | 优先级 |
|---------|--------|-------|
| 编程/代码 | java, python, 代码, bug | 优先 DeepSeek |
| 翻译 | 翻译, translate | 任意 |
| 写作/创作 | 写文章, 作文, 故事 | 优先 千问 |
| 数学/计算 | 算, 计算, 多少 | 优先 DeepSeek |
| 通用 | 其他 | 轮询或默认 |

### 4.2 `TaskClassifier`

基于关键词和场景分类用户任务类型，帮助路由器做决策。分类维度包括：
- 任务复杂度 (简单/中等/复杂)
- 是否需要推理能力
- 是否需要搜索增强
- 是否需要多模态能力

### 4.3 模型自助管理面（2026-08-12）

运营侧无需改 YAML 即可接入/管理模型提供商，来源模型 **YAML 兜底 + DB 覆盖**：

| 组件 | 说明 |
|------|------|
| `LlmProviderAdminService` / `LlmRoutingRepository`（chat-llm） | DB 三表（`llm_provider_config` / `llm_provider_props` / `llm_model_config`）读写 + 注册中心同步；`ApplicationReadyEvent` 自动加载 DB 覆盖 YAML |
| `LlmProviderAdminController` | `/api/v1/llm/admin/providers` 增删改查 + `/types`（策略工厂 `supportedTypes()`）+ `/reload` 全量重载 |
| chat-web 代理 | `LlmAdminProxyController` 透传（前端不可直达 chat-llm） |
| 前端「模型管理」页 | `AdminModels.jsx` 动态管理：提供商卡片、新增/编辑弹窗、模型动态行、删除、重载 |

接入一个厂商的完整路径（零代码）：管理页新增提供商（类型下拉选 `rest`/`sdk`）→ 填 baseUrl + apiKey + 模型列表 → 保存即时生效。apiKey 存 `llm_provider_props`（SECRET），列表仅回脱敏值。

---

## 五、容错机制

### 4.1 熔断器 (`CircuitBreaker`)

```
        正常
         │
    [连续失败 N 次]
         │
         ▼
       熔断打开
    (拒绝新请求)
         │
    [等待时间窗口结束]
         │
         ▼
       半开状态
    (放行少量请求探测)
         │
    ┌────┴────┐
    ▼         ▼
   成功      失败
    │         │
   关闭    重新熔断
```

### 4.2 自愈服务 (`SelfHealingService`)

| 错误类型 | 自愈策略 |
|---------|---------|
| `NETWORK_ERROR` | 自动重试，指数退避 |
| `TIMEOUT` | 延长超时时间重试 |
| `RATE_LIMITED` | 等待后重试 |
| `AUTH_ERROR` | 触发告警，不自动重试 |
| `MODEL_OVERLOADED` | 切换备用模型 |

### 4.3 错误聚合 (`ErrorAggregator`)

按模型维度聚合错误，统计指标：
- 错误类型分布
- 错误频率趋势
- 模型可用性百分比

---

## 六、关键依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| chat-llm (自研) | - | 多 Provider LLM 服务、图执行引擎、RAG、gRPC (:9095/:9195) |
| LangChain4j | - | 个人对话 / 树洞 ChatMemory (chat-core `langchain4j/`) |
| grpc-java | - | chat-llm 对外 gRPC 服务 (rag/llm, :9195) |
| Jackson | - | JSON 序列化/反序列化 |
| java.net.http | JDK 内置 | OpenAI 兼容 Provider 的 HTTP 客户端 |

---

## 七、已知问题与修复记录

### 6.1 JSON 控制字符导致解析失败

**现象**：LLM 返回的 JSON 中包含 CTRL-CHAR (code 31) 等控制字符，Jackson 解析失败

**修复**：Provider/客户端均添加 `cleanJson()` 方法：
- `OpenAICompatProvider.java`（chat-llm）
- `OpenAISdkProvider.java`（chat-llm）
- `DirectLLMClient.java`（chat-core）

方法过滤 ASCII 范围 0x00-0x1F（保留 `\t` `\n` `\r`）的控制字符。

### 6.2 Gzip 压缩响应乱码

**现象**：`OpenAICompatProvider` 设置 `Accept-Encoding: gzip`，部分 API 返回 gzip 压缩数据，`BodyHandlers.ofString()` 无法解压导致乱码

**修复**：删除 `OpenAICompatProvider` 中的 `.header("Accept-Encoding", "gzip")` 行

### 6.3 线程池任务被静默丢弃

**现象**：`ThreadPoolFactory` 使用 `DiscardPolicy`，队列满时任务被静默丢弃

**修复**：改为 `CallerRunsPolicy` + 队列满时打 ERROR 日志

```java
RejectedExecutionHandler handler = (r, executor) -> {
    log.error("[ThreadPool] {} 线程池队列满!", threadPrefix);
    if (!executor.isShutdown()) { r.run(); }
};
```

### 6.4 Security 401 拦截

**现象**：`POST /api/v1/debate` 返回 401

**修复**：在 `SecurityConfig` 白名单中添加：
```java
.requestMatchers("/api/v1/debate/**").permitAll()
.requestMatchers("/api/v1/monitor/**").permitAll()
```

### 6.5 缺失 import 导致编译失败 (2026-08, JDK 26)

**现象**：升级 JDK 26 后 `mvn clean install` 编译失败，`Locale`、`ModelConfig`、`HashMap`、`HashSet` 等符号无法解析

**影响文件**：
- `ErrorType.java`、`FileContentExtractor.java`、`DocumentParser.java` — 使用 `Locale.ROOT` 但未 import
- `TreeHoleService.java` — 使用 `ModelConfig` 但未 import
- `TaskClassifier.java` — 同 `Locale` 问题
- `MediaGenController.java` — `HashMap`/`ArrayList`/`HashSet`/`Arrays`/`Set` 未 import

**修复**：为所有文件添加显式 `import` 声明。JDK 26 更加严格，不再允许隐式类型引用。

---

## 八、思考链展示 (Thinking Chain Display)

### 8.1 功能说明

当用户提出复杂问题时，系统自动记录 LLM 的分析路径与推理过程，以灰色字展示在最终答案之前，让用户不仅看到结果，也能看懂得出答案的逻辑。

**适用场景**：个人对话空间（ChatProcessor）+ 情绪树洞（TreeHoleService）

### 8.2 触发机制

思考链由**意图识别结果**自动驱动。以下四种复杂意图会启用思考链模式：

| 意图 | 触发条件 | 说明 |
|------|---------|------|
| `REASONING` | 数学/逻辑推理 | 需要分步推导 |
| `CODE_GENERATION` | 代码生成/调试 | 需要分析需求 |
| `KNOWLEDGE_QA` | 知识问答 | 需要知识检索与综合 |
| `TASK_EXECUTION` | 工具调用/任务编排 | 需要规划步骤 |

简单意图（`GENERAL_CHAT`、`EMOTIONAL_SUPPORT`、`CREATIVE_WRITING`、`SUMMARIZATION`、`TRANSLATION`、`UNKNOWN`）不启用思考链，LLM 直接输出。

> **注意**：情绪树洞（TreeHoleService）**始终**启用思考链，不依赖意图判定，确保情感类回复的推理过程对用户可见。

### 8.3 核心组件：ThinkingStreamParser

`ThinkingStreamParser` 是一个**状态机**，逐 token 解析 LLM 流式输出，分离 `<thinking>` 标签内外的内容：

```
                    feed("hello")
    ┌─────────┐ ────────────────► ┌─────────┐
    │ NORMAL  │                   │ NORMAL  │ → onAnswerToken("hello")
    └────┬─────┘                   └─────────┘
         │ feed("<thinking>")
         ▼
    ┌─────────┐                   ┌─────────────┐
    │ IN_TAG  │ ─── feed("分析") ──► │ IN_TAG     │ → onThinkingToken("分析")
    └────┬─────┘                   └─────────────┘
         │ feed("</thinking>")
         ▼
    ┌────────────┐                 ┌────────────┐
    │ TAG_CLOSED │ ─ feed("答案") ─► │ TAG_CLOSED │ → onAnswerToken("答案")
    └────────────┘                 └────────────┘
```

**状态机三态**：
- `NORMAL`：未检测到 `<thinking>` 标签，直接输出为 answer
- `IN_TAG`：在 `<thinking>` 标签内，输出为 thinking token
- `TAG_CLOSED`：已闭合 `</thinking>`，后续输出为 answer

**安全降级机制**：
- LLM 在 300 字符后仍未输出 `<thinking>` 标签 → 自动标记为非思考模式，避免无限等待
- `markAsNonThinking()` 可手动强制关闭思考模式
- 缓冲最后 11 字符防止标签被跨 chunk 切断

### 8.4 WebSocket 消息流

```
Client                          ChatProcessor                    LLM
  │                                  │                             │
  │──── stream_start ───────────────►│                             │
  │                                  │──── prompt + system ───────►│
  │                                  │                             │
  │◄─── thinking_start ─────────────│  (检测到<thinking>)         │
  │◄─── thinking_token("分析步骤1")─│                             │
  │◄─── thinking_token("分析步骤2")─│                             │
  │◄─── thinking_token("结论...")───│                             │
  │                                  │◄──── </thinking> ──────────│
  │◄─── stream_token("答案：42")────│                             │
  │◄─── done ─────────────────────│                             │
  │                                  │                             │
```

新增 WebSocket 消息类型：

| 消息类型 | 方法 | payload |
|---------|------|---------|
| `thinking_start` | `WsMessage.thinkingStart()` | `{"type":"thinking_start"}` |
| `thinking_token` | `WsMessage.thinkingToken(token)` | `{"type":"thinking_token","token":"..."}` |

### 8.5 LLM Prompt 注入

复杂意图场景下，system prompt 末尾追加：

```
如果问题复杂需要推理分析，请先把分析路径写在 <thinking>...</thinking> 标签中，
然后再给出最终答案。如果问题简单则直接回答。
```

LLM **自主决定**是否输出 `<thinking>` 标签：
- 复杂问题 → 生成标签 → `ThinkingStreamParser` 分离 → 前端灰色展示
- 简单问题 → 不生成标签 → 直接流式输出答案（零额外开销）

### 8.6 前端渲染

```css
.thinking-block {
    color: #6b7280;                    /* 灰色 */
    font-style: italic;                /* 斜体 */
    font-size: 13px;                   /* 小号字 */
    border-left: 2px solid #d1d5db;    /* 左边框 */
    background: rgba(107,114,128,0.05); /* 淡灰背景 */
    padding: 8px 12px;
    margin-bottom: 8px;
}
```

`PersonalChat.jsx` 和 `TreeHole.jsx` 处理 `thinking_token` 消息，将 token 累积到当前消息的 `thinking` 字段，渲染在 AI 气泡顶部。

### 8.7 数据存储

- 思考链内容（`<thinking>` 标签内）**不存入数据库**，仅作为实时展示
- `answerCollector` 分离纯回答文本存入 DB，确保历史记录干净
