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
LLMStrategyFactory (策略工厂)
    │
    ├── OpenAICompatStrategy  →  千问 (Qwen) / DeepSeek
    ├── DoubaoStrategy        →  豆包 (Doubao)
    └── DirectLLMClient       →  直接 HTTP 调用 (备用)
```

---

## 二、LLM 策略

### 2.1 `LLMStrategy` 接口

统一抽象，定义三个核心方法：
- `callLLM(messages, modelConfig)` — 同步调用
- `callLLMWithImage(messages, imageUrl, modelConfig)` — 带图片的多模态调用
- `callLLMStream(messages, modelConfig, callback)` — 流式调用

### 2.2 `OpenAICompatStrategy`

**适用模型**：千问 (Qwen)、DeepSeek 等兼容 OpenAI API 格式的模型

**特点**：
- 使用 `java.net.http.HttpClient` 直接构建 HTTP 请求
- 通过 `modelConfig` 中的 `apiKey`、`baseUrl` 动态配置
- 支持流式 (SSE) 与非流式两种模式
- 支持携带图片的多模态调用
- **重要**：**不设置** `Accept-Encoding: gzip`，因为 `BodyHandlers.ofString()` 不会自动解压

### 2.3 `DoubaoStrategy`

**适用模型**：豆包 (Doubao)

**特点**：
- 使用豆包专用 SDK
- 独立 JSON 解析逻辑，处理豆包特有响应格式
- 包含 `cleanJson()` 方法过滤控制字符

### 2.4 `DirectLLMClient`

**用途**：直接 HTTP 调用的通用客户端（备用方案）

**特点**：
- 不依赖策略工厂，直接构建 HTTP 请求
- 用于不需要策略路由的简单场景

---

## 三、模型路由

### 3.1 `ModelRouter` (关键词智能路由)

根据用户输入的关键词和场景规则，自动选择合适的 LLM 模型。

路由规则：
| 任务类型 | 关键词 | 优先级 |
|---------|--------|-------|
| 编程/代码 | java, python, 代码, bug | 优先 DeepSeek |
| 翻译 | 翻译, translate | 任意 |
| 写作/创作 | 写文章, 作文, 故事 | 优先 千问 |
| 数学/计算 | 算, 计算, 多少 | 优先 DeepSeek |
| 通用 | 其他 | 轮询或默认 |

### 3.2 `TaskClassifier`

基于关键词和场景分类用户任务类型，帮助路由器做决策。分类维度包括：
- 任务复杂度 (简单/中等/复杂)
- 是否需要推理能力
- 是否需要搜索增强
- 是否需要多模态能力

---

## 四、容错机制

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

## 五、关键依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| LangChain4j | - | LLM 调用框架、ChatMemory |
| LangGraph4j | - | 辩论场图式工作流 (DebateGraphService) |
| Jackson | - | JSON 序列化/反序列化 |
| java.net.http | JDK 内置 | OpenAICompatStrategy 的 HTTP 客户端 |

---

## 六、已知问题与修复记录

### 6.1 JSON 控制字符导致解析失败

**现象**：LLM 返回的 JSON 中包含 CTRL-CHAR (code 31) 等控制字符，Jackson 解析失败

**修复**：三个策略文件均添加 `cleanJson()` 方法：
- `OpenAICompatStrategy.java`
- `DoubaoStrategy.java`
- `DirectLLMClient.java`

方法过滤 ASCII 范围 0x00-0x1F（保留 `\t` `\n` `\r`）的控制字符。

### 6.2 Gzip 压缩响应乱码

**现象**：`OpenAICompatStrategy` 设置 `Accept-Encoding: gzip`，部分 API 返回 gzip 压缩数据，`BodyHandlers.ofString()` 无法解压导致乱码

**修复**：删除 `OpenAICompatStrategy` 中的 `.header("Accept-Encoding", "gzip")` 行

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
- `LLMStrategyFactory.java`、`TaskClassifier.java` — 同 `Locale` 问题
- `MediaGenController.java` — `HashMap`/`ArrayList`/`HashSet`/`Arrays`/`Set` 未 import

**修复**：为所有文件添加显式 `import` 声明。JDK 26 更加严格，不再允许隐式类型引用。
