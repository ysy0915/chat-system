# chat-llm 独立使用指南

> chat-llm 可脱离完整项目独立运行，作为通用 LLM 网关使用。
> 只需配置 API Key，无需 MySQL/Redis/RabbitMQ/Neo4j/Milvus。
> 模型管理面 / RAG 检索 / 对话记忆 / 知识图谱 均内置**纯内存实现**（零外部依赖），开箱即用。

## 快速启动

```bash
# 1. 打包
cd chat-system-project
mvn clean install -DskipTests -pl chat-llm -am

# 2. 启动（standalone 模式）
export DEEPSEEK_API_KEY=sk-your-key
export QWEN_API_KEY=sk-your-key
# export DOUBAO_API_KEY=your-key

java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=standalone
```

启动后访问：
- 服务端口：http://localhost:9095
- 健康检查：http://localhost:9095/actuator/health
- Swagger UI：http://localhost:9095/swagger-ui.html

## API 接口

### 非流式调用

```bash
curl -X POST http://localhost:9095/api/v1/chain/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "deepseek",
    "model": "deepseek-chat",
    "messages": [
      {"role": "user", "content": "你好，介绍一下你自己"}
    ],
    "temperature": 0.7
  }'
```

### SSE 流式调用

```bash
curl -N -X POST http://localhost:9095/api/v1/chain/stream \
  -H "Content-Type: application/json" \
  -d '{
    "provider": "qwen",
    "model": "qwen-plus",
    "messages": [
      {"role": "user", "content": "写一首关于秋天的诗"}
    ],
    "temperature": 0.9
  }'
```

### 图执行引擎（多节点编排）

```bash
curl -X POST http://localhost:9095/api/v1/chain/graph/invoke \
  -H "Content-Type: application/json" \
  -d '{
    "nodes": [
      {"id": "thinker", "provider": "deepseek", "model": "deepseek-chat",
       "prompt": "分析这个问题：{{input}}"},
      {"id": "writer", "provider": "qwen", "model": "qwen-plus",
       "prompt": "基于分析结果写回答：{{thinker.output}}", "dependsOn": ["thinker"]}
    ],
    "input": "如何学习机器学习"
  }'
```

## 配置模型

编辑 `application-standalone.yml` 或通过环境变量配置：

```yaml
llm:
  providers:
    - name: deepseek          # 提供商名称
      type: rest              # 调用方式: rest | sdk
      base-url: https://api.deepseek.com
      path: /chat/completions # API 路径
      api-key: ${DEEPSEEK_API_KEY}
      models:                 # 支持的模型列表
        - deepseek-chat
        - deepseek-reasoner

    - name: qwen
      type: rest
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      path: /v1/chat/completions
      api-key: ${QWEN_API_KEY}
      models: [qwen-plus, qwen-max, qwen-turbo]

    # OpenAI 官方
    - name: openai
      type: rest
      base-url: https://api.openai.com
      path: /v1/chat/completions
      api-key: ${OPENAI_API_KEY}
      models: [gpt-4o, gpt-4o-mini]

    # Ollama 本地模型
    - name: ollama
      type: rest
      base-url: http://127.0.0.1:11434
      path: /v1/chat/completions
      api-key: ollama
      models: [llama3, qwen2.5]
```

### 新增自定义 Provider

实现 `LLMProviderStrategy` 接口 + `LLMProviderFactory`，标注 `@Component`，Spring 自动收集注册：

```java
@Component
public class MyProvider implements LLMProviderStrategy {
    @Override
    public String name() { return "my-provider"; }

    @Override
    public boolean supports(String provider, String model) {
        return "my-provider".equalsIgnoreCase(provider);
    }

    @Override
    public LangChainResponse invoke(LangChainRequest request) {
        // 自定义调用逻辑
    }

    @Override
    public void invokeStream(LangChainRequest request,
                             Consumer<String> chunkConsumer,
                             Runnable onComplete,
                             Consumer<Throwable> onError) {
        // 自定义流式逻辑
    }
}

@Component
public class MyProviderFactory implements LLMProviderFactory {
    @Override
    public String type() { return "my-type"; }

    @Override
    public LLMProviderStrategy create(ProviderConfig config, ObjectMapper mapper) {
        return new MyProvider();
    }
}
```

## 功能说明

| 功能 | standalone 模式 | 完整模式 |
|------|:---:|:---:|
| 多模型调用（rest/sdk） | ✅ | ✅ |
| SSE 流式输出 | ✅ | ✅ |
| 图执行引擎（多节点编排） | ✅ | ✅ |
| 熔断/重试/限流（Resilience4j） | ✅ | ✅ |
| gRPC 接口 | ✅ | ✅ |
| Prometheus 指标 | ✅ | ✅ |
| 模型管理面（DB CRUD） | ✅（纯内存） | ✅ |
| RAG 知识库检索 | ✅（纯内存向量库） | ✅（Milvus） |
| 对话记忆（短期/长期/画像） | ✅（纯内存 KV + 向量库） | ✅（Redis + Milvus） |
| 知识图谱 | ✅（纯内存图） | ✅（Neo4j） |

## 增强能力（纯内存实现）

standalone 模式默认启用以下能力（`application-standalone.yml`）：

```yaml
app:
  rag:
    enabled: true
    backend: memory          # milvus=生产向量库（需外部依赖），memory=纯内存向量库
    embedding:
      api-key: ${EMBEDDING_API_KEY:${QWEN_API_KEY:}}  # 向量化可复用 LLM Key（DashScope text-embedding-v3）
  knowledge-graph:
    enabled: true
    backend: memory          # neo4j=生产图谱（需外部依赖），memory=纯内存图
  llm:
    admin:
      memory: true           # 模型管理面使用纯内存仓储（DB 模式自动使用 MySQL 三表）
  mapper-scan:
    enabled: false           # 无 DB，关闭 MyBatis Mapper 扫描
```

> 数据仅存内存，重启即清空；生产请使用 milvus/neo4j backend 并接入 MySQL。

### 1. 模型管理面（DB CRUD → 内存 CRUD）

与完整模式同一套 API，增删改查写内存（`InMemoryLlmRoutingRepository`），apiKey 同样不回传：

```bash
# 新增提供商（providerName/baseUrl 必填）
curl -X POST http://localhost:9095/api/v1/llm/admin/providers \
  -H "Content-Type: application/json" \
  -d '{"providerName":"ollama","providerType":"openai","baseUrl":"http://localhost:11434/v1","apiKey":"","models":[{"modelName":"llama3","displayName":"Llama 3"}]}'

# 列表（YAML 静态 + 内存 DB 合并视图）
curl http://localhost:9095/api/v1/llm/admin/providers
```

### 2. RAG 知识库检索（纯内存向量库）

知识库 CRUD 走 MySQL（无 DB 时用 `InMemoryRAGRepository`），文档分片 + 向量化后存内存向量库（余弦相似度 TopK）：

```bash
# 创建知识库
curl -X POST http://localhost:9095/api/v1/rag/kb \
  -H "Content-Type: application/json" \
  -d '{"name":"demo","description":"演示库"}'

# 内部检索（chat-core 同款接口）
curl -X POST http://localhost:9095/internal/rag/search \
  -H "Content-Type: application/json" \
  -d '{"kbId":1,"query":"如何部署","topK":3}'
```

### 3. 对话记忆（短期 KV + 长期事实向量）

短期记忆存内存 KV（`InMemoryMemoryKVStore`，Redis 版自动降级），长期记忆 = LLM 抽取事实 → 向量化 → 内存向量库召回：

```bash
# 保存一轮对话（异步抽取用户事实）
curl -X POST http://localhost:9095/internal/rag/memory/save \
  -H "Content-Type: application/json" \
  -d '{"scene":"chat","userId":1,"question":"我是Java开发者","answer":"好的！"}'

# 取记忆上下文（短期 + 长期合并）
curl -X POST http://localhost:9095/internal/rag/memory/context \
  -H "Content-Type: application/json" \
  -d '{"scene":"chat","userId":1,"question":"你好"}'

# 语义召回用户事实（供 System Prompt 注入）
curl -X POST http://localhost:9095/internal/rag/memory/facts/recall \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"question":"我的职业","topK":3}'
```

### 4. 知识图谱（纯内存图）

`InMemoryKnowledgeGraphService` 实现 `GraphStore` 门面，图结构存 `ConcurrentHashMap`：

```bash
curl http://localhost:9095/internal/graph/stats        # {"entityCount":0,"relationCount":0}
curl "http://localhost:9095/internal/graph?limit=50"    # 全图
curl -X POST http://localhost:9095/internal/graph/extract -H "Content-Type: application/json" \
  -d '{"messageId":1,"question":"...","answer":"...","source":"demo"}'
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVER_PORT` | 服务端口 | 9095 |
| `GRPC_PORT` | gRPC 端口（-1 关闭） | 9195 |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | |
| `QWEN_API_KEY` | 千问 API Key | |
| `DOUBAO_API_KEY` | 豆包 API Key | |
| `OPENAI_API_KEY` | OpenAI API Key | |
| `EMBEDDING_API_KEY` | RAG/记忆向量化 API Key（缺省回退 `QWEN_API_KEY`） | |

## 架构

```
请求 → LangChainController → LLMInvokeService → LLMProviderRegistry
                                                    ↓
                                          LLMProviderStrategyFactory
                                            ↓           ↓
                                  OpenAICompatProvider  OpenAISdkProvider  (自定义SPI)
                                            ↓
                                     HTTP/SSE → LLM API
```

- **路由**：provider 精确 → model 精确 → 默认模型 → 全局默认
- **熔断**：50% 失败率 → 30s 熔断 → 半开探测恢复
- **重试**：最多 2 次，退避 1s
- **限流**：10 次/秒
