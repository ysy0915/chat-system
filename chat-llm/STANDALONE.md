# chat-llm 独立使用指南

> chat-llm 可脱离完整项目独立运行，作为通用 LLM 网关使用。
> 只需配置 API Key，无需 MySQL/Redis/RabbitMQ/Neo4j/Milvus。

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
| 模型管理面（DB CRUD） | ❌ | ✅ |
| RAG 知识库检索 | ❌ | ✅ |
| 对话记忆（短期/长期/画像） | ❌ | ✅ |
| 知识图谱（Neo4j） | ❌ | ✅ |

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `SERVER_PORT` | 服务端口 | 9095 |
| `GRPC_PORT` | gRPC 端口（-1 关闭） | 9195 |
| `DEEPSEEK_API_KEY` | DeepSeek API Key | |
| `QWEN_API_KEY` | 千问 API Key | |
| `DOUBAO_API_KEY` | 豆包 API Key | |
| `OPENAI_API_KEY` | OpenAI API Key | |

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
