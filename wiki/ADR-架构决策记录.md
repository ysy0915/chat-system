# 架构决策记录（ADR）

> 记录本系统关键架构决策的**背景 / 决策 / 后果**，便于追溯"为什么这么做"。
> 格式参考 Michael Nygard ADR 模板（Context / Decision / Consequences）。
> 维护约定：新决策追加编号（ADR-NNN），状态为 `Proposed` → 落地后改 `Accepted`；被替代/废弃标 `Deprecated` / `Superseded by ADR-NNN`。

---

## ADR-001 模块化微服务拆分

- **状态**：Accepted（2026-08 前期落地）
- **背景**：单体应用耦合 LLM 调用、业务编排、Web 接入、游戏、多模态，一处改动牵动全量发布；多团队并行开发互相阻塞。
- **决策**：按职责拆分为 `chat-common`（公共库）/ `chat-core`（核心 AI 业务编排）/ `chat-web`（Web 接入层）/ `chat-llm`（独立 LLM 服务）/ `chat-games`（游戏）/ `chat-media`（多模态）6 个 Maven 模块，公共能力下沉 chat-common（JWT、限流、内容安全、跨节点广播，114 类）。
- **后果**：✅ 模块独立打包/发布/重启（`restart-*.sh` 按需重启）；✅ 编译与测试隔离；⚠️ 跨模块调用增多（RagClient / CoreClient / LlmBundleClient HTTP 转发），需维护接口契约。

## ADR-002 前后端完全分离

- **状态**：Accepted
- **背景**：前端资源打包进 chat-web jar 导致每次前端改动都要重新打包上传整个 jar（chat-web 曾 72M），且无法独立灰度。
- **决策**：前端构建产物输出到 `frontend/dist/`，独立部署在主服务器 Nginx（`/opt/app/static/chat/`）；chat-web jar 不再包含前端资源（63M）；`/api/`、`/ws/` 由 Nginx 反代到后端；前端配置中心 `frontend/src/config/api.js` + `.env.development/.production`。
- **后果**：✅ 前后端独立发布、独立回滚；✅ 构建产物减小；⚠️ 引入跨域/CORS 与 Nginx 反代配置维护成本。

## ADR-003 LLM/RAG/知识图谱运行时下沉 chat-llm

- **状态**：Accepted
- **背景**：chat-core 同时承担业务编排与 LLM 协议适配、RAG 检索、Neo4j 知识图谱，职责过重；不同模块的 Embedding 维度（1024/1536）相互耦合。
- **决策**：chat-llm 独立承载多 Provider 调用 / 图执行引擎 / RAG / 知识图谱（REST :9095 + gRPC :9195）；chat-core 经 `RagClient`（/internal/rag/*）、`GraphClient`（/internal/graph/*）、`LlmBundleClient`（/api/v1/chain/*）跨进程调用；chat-web 知识库管理经 `CoreClient` 代理到 chat-llm；降级路径 `DirectLLMClient` 直连兜底。
- **后果**：✅ 业务层解耦、Provider 弹性（熔断/重试）按服务隔离；✅ 两套 Embedding 维度隔离；⚠️ 服务间 HTTP 调用增加链路延迟与故障点（引入 fail-open 与熔断兜底）。

## ADR-004 WebSocket Session 无状态化（Redis 化）

- **状态**：Accepted
- **背景**：WebSocket Session 存本地内存（ConcurrentHashMap），chat-web 多实例部署后 Session 不互通，连接漂移即断线。
- **决策**：`WebSocketSessionTracker` 将 Session 迁移 Redis（`ws:session:page` Hash + `ws:page:{page}` Set + 心跳 + 已知页面），15 分钟空闲自动清理；`/actuator/health/readiness` 就绪探针验证 Redis 读写。
- **后果**：✅ chat-web 无状态可水平扩展（生产 8081/8082）；✅ 重启不丢连接登记；⚠️ 引入 Redis 依赖（fail-open 放行降级）。

## ADR-005 双 core 高可用（9090 主 + 9092 从）

- **状态**：Accepted（2026-08-12 落地）
- **背景**：单 core 实例故障即全站 AI 能力不可用；9091 端口被 milvus-standalone 容器占用。
- **决策**：core 双实例：9090（主，Xmx768m，autoChat 定时任务仅此执行）+ 9092（从，Xmx512m）；web 以 `--app.core.base-urls=http://127.0.0.1:9090,http://127.0.0.1:9092` 轮询；`restart-core.sh [9090|9092|all]` 按 server.port 精确 pkill。
- **后果**：✅ 计算水平扩展、单实例故障降级切换；✅ 定时任务不重复；⚠️ 主从内存不对称，主实例冷启动慢（health-check 需容忍 9090 启动超时误报）。

## ADR-006 nodeId 固定 `node-{port}`（RabbitMQ 队列堆积根治）

- **状态**：Accepted（2026-08-12 落地）
- **背景**：跨节点队列名含随机 UUID，每次重启生成新队列，旧队列无人消费 → RabbitMQ 曾堆积 168 万条。
- **决策**：`CrossNodeConfig.nodeId()` 固定返回 `node-{serverPort}`，队列名 `cross-node-{nodeId}` 重启不变。
- **后果**：✅ 队列堆积根除；✅ 重启即复用旧队列；⚠️ 端口变更需同步队列清理。

## ADR-007 stop 请求广播到所有 core 实例

- **状态**：Accepted
- **背景**：双 core 下 stop 请求可能打到非承载该 reqId 的实例，导致"停止失效"。
- **决策**：chatStop / treeHoleStop 改用 `CoreClient.broadcast()` 广播所有 core 实例，任一成功即成功、全失败才抛异常。
- **后果**：✅ 停止请求可靠命中承载实例；✅ 前端收到确定性结果；⚠️ 广播增加少量网络开销。

## ADR-008 Prometheus 监控栈 + 钉钉告警

- **状态**：Accepted（2026-08-12 上线）
- **背景**：巡检依赖手工 ssh tail 日志，故障发现滞后；无历史指标可回溯。
- **决策**：Milvus 服务器部署 `--network host` Prometheus 栈（Prometheus:9094 / Alertmanager:9093 / node-exporter:9100 / blackbox-exporter:9115 / alert-webhook:9950），6 条告警规则（宕机 / 内存<8% / 磁盘<15% / JVM 堆 / CPU / 负载）→ 钉钉；`micrometer-registry-prometheus` 锁定 1.11.6（与 Boot 3.1.6 的 micrometer-core 匹配）。
- **后果**：✅ 指标化巡检替代手工 tail，4 个 Java 服务 /actuator/prometheus 全 200；✅ 钉钉秒级告警；⚠️ 与 core 共用 9090 冲突（Prometheus 改 9094）、Alertmanager 需禁集群端口、数据目录需 chown 65534。

## ADR-009 LLM Provider 策略模式 + SPI 策略工厂

- **状态**：Accepted（2026-08-12 落地）
- **背景**：`LLMProviderRegistry.init()` 内按 `isSdk()` 硬编码分支创建 Provider，新增厂商需改注册中心代码。
- **决策**：`LLMProviderFactory` SPI 扩展点（`type()` + `create(config, mapper)`）+ `LLMProviderStrategyFactory` 组合工厂（Spring Bean 自动收集 / 代码动态注册 / 未知类型回退 rest / `supportedTypes()` 供管理面）；新增厂商实现 `@Component` 工厂即零改动接入。
- **后果**：✅ 新增厂商零改动注册中心（千问/DeepSeek/豆包三厂商验证）；✅ 未知 type 回退不中断路由；⚠️ Spring 多构造器需 `@Autowired` 主构造器（单测直接 new 不暴露此坑）。

## ADR-010 模型自助管理面（DB 持久化 + 动态注册）

- **状态**：Accepted（2026-08-12 落地）
- **背景**：新增/修改模型需改 YAML 或调内部 API，运营无法自助操作；API Key 明文散落配置。
- **决策**：`llm_provider_config` / `llm_provider_props` / `llm_model_config` 三表持久化 + `LlmProviderAdminService`（DB 加载 / 写库 / 注册中心同步 / apiKey 脱敏）+ `/api/v1/llm/admin/providers` 管理 API + chat-web 代理 + 前端管理页；来源策略 YAML 兜底 + DB 覆盖；写操作 `X-Admin-Pass` 鉴权（chat-llm 纵深防御 `app.llm.admin-password`）。
- **后果**：✅ 运营自助增删改模型、即时生效、全量重载；✅ apiKey 仅存不读、列表只返回 `hasApiKey`；⚠️ 双实例写库后需 `/reload` 收敛（DB 为权威）。

## ADR-011 工具平台化（ToolDefinition 元数据 + DB 覆盖）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：Agent 工具通过代码注册，启停/参数调整需改代码发布；LLM 可见工具集合无法运行时调整。
- **决策**：`ToolDefinition` 元数据值对象（CODE/DB 双源）+ `tool_registry` 表（unique `uk_tool_name`）+ `ToolRegistry.applyDbOverrides()` 启动合并 DB 覆盖 + `/internal/tools` 管理面（GET/PUT/DELETE/reload）+ enabled 过滤（禁用工具 LLM 不可见不可调）。
- **后果**：✅ 工具启停/覆盖零发布；✅ 管理面可查可改；⚠️ 元数据双源需维护一致性（DB 优先）。

## ADR-012 存储 SPI 热插拔（StorageRegistry）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：向量库 / 图谱 / KV 存储直接在服务内硬编码实现，替换存储需改业务代码。
- **决策**：`Storage` 顶层 SPI 接口 + `VectorStore` / `GraphStore` / `KeyValueStore` 子接口 + `StorageRegistry`（Spring 自动收集 / 动态注册 / 同 type 覆盖 / 未知容错）；`LegacyVectorStoreService` / `KnowledgeGraphService` / `RedisKeyValueStore` 三实现即插即用（与 `LLMProviderStrategyFactory` 同范式）。
- **后果**：✅ 存储实现热插拔、新增存储零改动业务；✅ 注册中心统一管理；⚠️ 接口抽象需覆盖各存储能力差异。

## ADR-013 RAG 双体系二选一（保留 legacy，退役新版）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：legacy RAG（kbId 模型 + Milvus 1024 维）在生产在用，新版多数据源 RAG（`RagService` / `RAGController` / `RagGrpcService` / `rag.enabled` 开关）无任何调用方却长期并存，双体系增加维护与心智成本。
- **决策**：二选一**保留 legacy**；新版 RAG 代码全部下线（删除 18 个 Java/proto 文件 + `rag:` 配置段）；保留 `app.rag.enabled` 控制与 `KnowledgeController`。
- **后果**：✅ 单一 RAG 维护路径；✅ 代码量与配置面收敛；⚠️ 未来若需多数据源 RAG 需重新设计（本次决策未覆盖）。

## ADR-014 LLM 配置三源归一（`llm_*` 表为唯一运行时源）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：模型配置三源分立（`model_configs` 运行时源 + `llm_*` 管理面源 + Nacos YAML 兜底），改配置不知改哪、迁移易错（豆包 404 事故根因之一）。
- **决策**：`model_configs` 退役（仅存档），运行时统一读 `llm_*` 新表；YAML 仅作网关兜底；`ModelConfigRepository` 全部方法改查新表（39 个消费方零改动）；迁移脚本实时 SELECT 旧表数据（不硬编码密钥）；按厂商显式写 `path`（防 joinUrl 重叠 404 复现）。
- **后果**：✅ 单一运行时数据源、管理面/运行时同源；✅ 迁移不失效 Redis 个人模型绑定（模型 id 一致）；⚠️ 存量 DB 数据迁移需验证（本次已修复 doubao `model_name` 与 `path` 两个迁移缺陷）。

## ADR-015 意图识别三层漏斗 + SeedPool 自增强

- **状态**：Accepted
- **背景**：单一 LLM 分类意图延迟 200-1000ms 且昂贵；规则/语义匹配成本低但覆盖有限。
- **决策**：L1 规则（Trie/Regex，0-1ms）→ L2 语义（Embedding k-NN，30-80ms，动态阈值）→ L3 LLM 分类（200-1000ms）串行降级；高置信结果经 SeedPool 异步回灌 L2（Milvus）+ L1（Trie），L1+L2 目标 >95%；意图驱动 temperature 路由。
- **后果**：✅ 命中率与成本/延迟平衡，L3 兜底模糊意图；✅ 反馈闭环自增强；⚠️ 冷启动依赖种子预加载（~420 条）；L3 失败降级不阻塞主流程。

## ADR-016 树状辩论 Plan-and-Execute 混合编排

- **状态**：Accepted
- **背景**：树状辩论视角数量由 LLM 运行时动态决定（2-3 个），LangGraph4j 的 `StateGraph` 节点编译时固化，无法表达动态并行视角。
- **决策**：采用混合模式：`decompose()`（Plan：LLM 拆解视角）→ 视角间 Java `CompletableFuture` 并行（Execute）→ 每视角一个 `TreePerspectiveGraphService` LangGraph 子图（含 Reflection 反思节点）→ `aggregate()`（LLM 汇总）；标准辩论保持单一 `StateGraph`。
- **后果**：✅ 动态视角数可表达、可维护性优于全图预定义分支；✅ 单视角失败不影响其他视角、汇总失败本地拼接；⚠️ 图编排与 Java 编排两套逻辑并存，术语需对齐业界命名（见 CHANGELOG 十五）。

## ADR-017 Multi-Agent 并行工作流（TaskPlanner → RabbitMQ → Worker → 收敛）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：超长/跨域请求单 Agent 串行处理耗时且输出冗长；双实例间并发控制缺失导致资源错配。
- **决策**：`TaskPlanner.shouldDecompose`（长度 ≥ min-length + LLM 判定）拆解 ≤9 子任务 → RabbitMQ `agent.subtask` 分发 → 双实例 `SubAgentWorker`×10 并行 → `SubTaskResultCollector` Redis 幂等聚合 → 主 Agent 收敛压缩 ≤1000 字；Redis Lua 原子限流（`agent:workflow:active` ≤ max-concurrent=8，超限降级普通流程）+ manual ack + prefetch=1（公平分发零丢失）+ SETNX 收敛锁（双实例只收敛一次）+ `WorkflowReconciler` 30s 对账兜底。
- **后果**：✅ 复杂任务耗时/输出大幅压缩（T01-T06 全量 PASS=12/FAIL=0）；✅ 20 并发 = 精确 8 并行 + 12 降级无泄漏；✅ 子任务零丢失；⚠️ 工作流状态依赖 Redis（AOF 持久化 + DB 源对账列为后续加固项）。

## ADR-018 Reconciler ZSet 索引 + Worker DLX 死信指数退避重试

- **状态**：Accepted（2026-08-13 落地，ADR-017 的可靠性加固）
- **背景**：① Reconciler 全量 keys() 扫描 O(N)，plan 多时开销大；② Worker 失败 `nack(requeue=false)` 即终态，单子任务失败导致整个 plan 无法收敛。
- **决策**：① 新增 ZSet 索引 `agent:reconciler:plans`（score=下次检查时间戳，到齐置 0 立即纳入，收敛成功 ZREM），`ZRANGEBYSCORE 0 now LIMIT 500` 只取到期候选，存量兜底随 30min TTL 淘汰 → O(logN)；② Worker 失败投 `agent.subtask.dlx` 死信队列（per-message TTL 指数退避 1s→2s→4s→60s），`x-death` 累计次数达 max-attempts=5 才回传终态失败。
- **后果**：✅ 扫描从 O(N) 降 O(logN)；✅ 单子任务失败可重试不拖垮 plan；✅ 双 P1 软件扣分项清零（对应 `SubAgentWorkerTest` 9 例 + `WorkflowReconcilerTest` 3 例）；⚠️ DLX 队列参数变更（如 TTL 策略）需先删服务器旧队列，否则 406 PRECONDITION_FAILED。

## ADR-019 前后端分离部署 + 双服务器拓扑

- **状态**：Accepted
- **背景**：单服务器承载前端 + 全部后端 + 中间件，内存压力大（曾实测可用仅 822Mi）；故障域未隔离。
- **决策**：主服务器 your-nginx-ip 只跑 Nginx + Redis + RabbitMQ + 前端静态资源；Milvus 服务器 your-milvus-ip 跑全部 Java 服务（core×2 / web / llm / games / media）+ Nacos + Neo4j + Milvus + Prometheus 栈；Nginx `/api/`、`/ws/` 反代内网 your-intra-ip:8081。
- **后果**：✅ 前端/静态与后端故障域隔离；✅ 单点 Nginx 承载简单；⚠️ 主服务器单点无备份（P0 运维项转台账跟进，不入软件评分）。

## ADR-020 测试质量专项（反射/弱测试 → 真实行为断言）

- **状态**：Accepted（2026-08-13 收官 W1-W8）
- **背景**：存量测试含大量"类存在验证 / 反射（getMethod / Class.forName / assertNotNull(X.class)）/ 仅构造"弱测试，验证的是"类存在"而非"行为正确"，无法防回归。
- **决策**：全仓清理弱测试：W1 空壳清理（14 个）→ W2 chat-common 真实断言 → W5 Mapper 契约测试 + 弱测试升级 → W6 Agent 工具/observability 反射清零 → W7 games/media 反射清零 → W8 chat-web 反射/弱测试清零；统一 AAA 模式 + Mockito 5.14.2；废弃空类（如 ModelConfigController）随测试一并删除。
- **后果**：✅ 全仓 **700 例测试全绿**（chat-common 272 / chat-core 197 / chat-web 87 / chat-llm 74 / chat-games 44 / chat-media 26）；✅ 全库正则扫描无反射残留；⚠️ 测试从"存在性"转向"行为契约"后，实现细节变更需同步更新测试（成本转移）。

## ADR-021 业务级指标与业务告警（可观测性补强）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：ADR-008 的 Prometheus 栈告警以系统层为主（宕机/内存/磁盘/JVM/CPU/负载），业务运行质量（意图识别命中率、Multi-Agent 工作流降级/收敛、LLM 成本）无指标可查、无告警可依，运营只能事后 tail 日志。
- **决策**：chat-core 新增 `CoreBusinessMetrics`（MeterBinder，经 chat-common 传递依赖 micrometer-registry-prometheus 1.11.6，无新依赖），埋点意图漏斗分层命中/耗时（`core.intent.funnel.hits/latency`）与 Multi-Agent 工作流启动/降级/收敛（`core.agent.workflow.started/converged`）；`docs/prometheus-alert-rules.yml` 新增 `chat-system-business` 组 4 条告警（漏斗 L1+L2 命中率 <85% / 工作流降级率 >50% / 收敛失败 / LLM token 激增，LLM 侧复用 chat-llm 既有 `llm.invoke.tokens`）。
- **后果**：✅ 业务告警补齐，可观测性 4/5 → 5/5（架构评估报告综合 96 → 97、纯软件 97 → 98）；✅ `CoreBusinessMetricsTest` 6 例真实断言，chat-core 203 例全绿（后经 ADR-022 切面化 → 212 例全绿）；⚠️ 双实例各自上报需按 instance 聚合、告警阈值需按流量基线调优；队列深度告警待 RabbitMQ exporter（P3 可选排期）。

## ADR-022 业务指标采集切面化（AOP 横切埋点）

- **状态**：Accepted（2026-08-13 落地）
- **背景**：ADR-021 的业务指标采集为手写埋点，`IntentFunnelEngine` / `AgentWorkflowOrchestrator` 内直接调用 `CoreBusinessMetrics`，业务类掺杂可观测性逻辑，监控目标变更需改业务代码。
- **决策**：chat-core 引入 `spring-boot-starter-aop`，新增 `CoreBusinessMetricsAspect`（`@Aspect @Component`）承载全部业务埋点：`@Around` 三个切点（`recognize` → 漏斗命中/耗时、`tryParallelWorkflow` → 工作流启动、`converge` → 收敛成功/失败）从返回值/异常取数，回退业务类内全部手写埋点；`converge` 原 catch 吞异常改为抛出后由切面记 failed 不重抛（对外语义不变）。
- **后果**：✅ 业务类零侵入，指标采集收敛为横切关注点（增减指标只改切面一处）；✅ 指标名与 4 条业务告警不变，Prometheus 侧零影响；✅ `CoreBusinessMetricsAspectTest` 9 例，chat-core 212 例全绿；⚠️ Spring AOP 仅拦截外部 Bean 调用，新增切点需保证跨类调用（同类自调用会绕代理）。

## ADR-023 辩论场多模型化 + 树状博弈提速（2026-08-14）

- **状态**：Accepted（2026-08-14 上线）
- **背景**：辩论场固定豆包/DeepSeek/千问三方组队，模型池固化无选择余地；树状模式随机全池抽取导致 2/5 概率抽中本地 Ollama 慢模型（单次 10-12s），每视角 3 轮串行拖慢整体体验。
- **决策**：
  1. **标准辩论多模型化**：`DebateProcessor.resolveDebateModels(chatModels, modelCount, excludeLocal)` 从已配置 chat 模型随机抽取 N 个（`model_count` 透传，默认 3，限 3~6），动态分配参与者 id 0..N-1、整合模型 id=N；`round_start` 携带 `model_ids`，流式 token 按 `model_id` 路由
  2. **模型名中文展示**：`ModelRouter.modelDisplayName(provider, model)`（doubao→豆包、qwen→千问、deepseek→DeepSeek、ollama→自研、moonshot→Kimi 等），前端 `toCnModel` 渲染
  3. **树状模式固定快模型**：树状辩论 `excludeLocal=true` 排除 ollama，仅从豆包/DeepSeek/千问等快模型随机 3 个；`TreePerspectiveGraphService` 用 `Map<String, BranchInfo>` 动态映射正方/中立/反方分支，替代硬编码模型 id
- **后果**：✅ 辩论模型可自选（上限随已配置 chat 模型数，3~6）、随机组队多样性提升；✅ 树状博弈单次响应从 10s+ 降至 ~1s，卡顿根除；✅ 前端 `modelType === 'chat'` 过滤动态展示可用模型数；⚠️ 树状模式不再包含自研 Ollama 模型（若需纳入需优化其推理速度）。

## ADR-024 chat-llm @MapperScan annotationClass 约束（2026-08-15）

- **状态**：Accepted（2026-08-15 上线）
- **背景**：`LlmApplication.MapperScanConfig` 的 `@MapperScan` 扫描 `com.example.chat.llm.rag.legacy` 包时未限制 `annotationClass`，MyBatis 把包下所有接口（含无注解的领域接口 `MemoryKVStore` / `VectorStoreLegacy` / `UserFactMemory`）注册为 Mapper 代理 bean。`MemoryKVStore` 被注册为 `memoryKVStore`，与 `@Component` 的 `RedisMemoryKVStore`（`redisMemoryKVStore`）同时成为 `ConversationMemoryService.setMemoryStore()` 注入候选，报 "expected single matching bean but found 2" 冲突。本地 standalone profile 因 `app.mapper-scan.enabled=false` 关闭 MapperScan 不触发，生产 prod profile 才暴露。
- **决策**：`@MapperScan` 加 `annotationClass = Mapper.class`，仅注册带 `@Mapper` 注解的接口。已核实 `com.example.chat.repository` 下 10 个接口、`RAGRepository`、`LlmRoutingRepository` 均带 `@Mapper`，不受影响。
- **后果**：✅ 无注解领域接口不再被误注册为 Mapper bean，bean 冲突根除；✅ chat-llm 双实例 9095/9096 prod 启动正常（health UP）；⚠️ 新增 Mapper 接口必须标注 `@Mapper` 注解，否则不会被扫描注册（比无差别包扫描更安全，但要求开发者遵守注解约定）。

## ADR-025 性能与稳定性加固：缓存双写 + 流式 Token 合并 + Resilience4j 熔断（2026-08-15）

- **状态**：Accepted（2026-08-15 上线）
- **背景**：压测与代码走查发现：① 对话答案缓存因**读写 key 不一致**完全失效（`save` 写 `sha256(question::provider::model)` 模型级 key，`hitAndServe` 读 `sha256(question::model-pool)` 问题级 key，永不相等），每次请求穿透打 LLM；② 流式回答**每 token 一次 MQ 同步广播**，双 core 实例下消息量巨大；③ 自研熔断器仅"连续失败 5 次"无法识别慢请求与偶发抖动；④ `RuleBasedMatcher` 状态机无界增长、`DebateTreeProcessor` 无界线程池存在 OOM 风险；⑤ SQL 危险词正则误伤 `created_at` 等含子串字段。
- **决策**：
  1. **缓存双写**：`ChatCacheManager.save` 同时写问题级 key（供 `hitAndServe` 命中）+ 模型级 key（保留区分），哈希 key 不同源无冲突
  2. **流式 Token 合并**：新增 `StreamTokenBatcher`，thinking/answer token 按 **30ms 窗口 / 256 字符阈值**合并后广播一次，流式结束 `close()` 冲刷；广播在锁外执行不阻塞 LLM 流式线程
  3. **Resilience4j 熔断**：自研实现替换为 Resilience4j 滑动窗口失败率（窗口 10 / 最少 5 次 / 失败率 ≥50% → OPEN / 冷却 30s / HALF_OPEN 放行 3 次），外部 API 不变（`LLMInvoker` 无感），指标自动上报 Prometheus
  4. **消费可靠性**：`ChatRequestConsumer` 改 MANUAL ack + `basicNack(requeue=false)`，失败消息进死信队列（DLX 指数退避），杜绝消费者重启丢消息
  5. **内存/并发防护**：`RuleBasedMatcher` 状态条目带最后活跃时间，30 分钟 TTL 定时清理；`DebateTreeProcessor` 无界队列 → `ThreadPoolFactory` 有界队列 + CallerRuns，批量池类级复用
  6. **连接池扩容**：HikariCP `maximum-pool-size` chat-common/chat-llm 10→20（nacos 同步），chat-core prod 30
  7. **SQL 危险词词边界**：`SqlExecutorController` 危险词匹配改 `\b` 词边界正则
- **后果**：✅ 缓存从"永远失效"恢复真实命中；✅ MQ 消息量降一个数量级（前端延迟 ≤30ms 无感知）；✅ 熔断能识别慢请求/偶发抖动，指标可观测；✅ 失败消息可重试不丢失、状态机/线程池无 OOM 风险；⚠️ 缓存双写多一次 Redis set（可忽略）；⚠️ 流式合并广播需保证超时冲刷（30ms 窗口），极端低延迟场景延迟略增；⚠️ `V1.2.0` DDL（req_id 唯一索引 + question ngram 全文索引）已于 2026-08-15 执行完成；chat-core 257 / 全量 892 用例全绿。

## ADR-026 安全加固：方法级鉴权 + WebSocket 鉴权 + 日志脱敏 + fail-close（2026-08-16）

- **状态**：Accepted（2026-08-16 上线）
- **背景**：代码走查发现多处「文档承诺但代码缺失」的安全硬伤：① `@EnableMethodSecurity` 被注释，方法级鉴权全缺失，管理接口用明文 `String.equals` 比密码（时序侧信道）；② WebSocket `/ws/**` permitAll，握手只读 `userId` 不验 JWT，可伪造 userId 订阅他人私有消息；③ 日志打印 JWT/OSS 签名 URL/密钥无脱敏；④ 配置文件硬编码默认密码（DB root、中间件默认口令），且 `.gitleaks.toml` 豁免了 docker-compose；⑤ 文件上传无类型校验、OSS 转存无 SSRF 防护；⑥ 内容安全异常 fail-open（放行）且只检输入不检输出。
- **决策**：
  1. **方法级鉴权**：恢复 `@EnableMethodSecurity(prePostEnabled=true)`（无注解方法不受影响，`/ws/**` 已 URL 层 permitAll，SockJS 不触发方法级拦截）；`AdminAuthUtil` 改 `MessageDigest.isEqual` 常量时间比较，消除时序侧信道
  2. **WebSocket 双层鉴权**：握手拦截器读 query `token`（SockJS 无法设 Header），有效则绑定 token 中 `uid`（忽略可伪造的 `userId` 参数）；匿名连接允许但仅限公开 topic；`ChannelInterceptor` 订阅级鉴权——私有 topic（`/topic/user.*`/`debate.*`/`treehole.*`）必须登录且 uid 匹配，防横向越权
  3. **日志脱敏**：新增 `MaskingMessageConverter`（logback 自定义 converter），`%msg` → `%maskedMsg`，遮蔽 JWT/签名 URL 参数/敏感键值对，明文+JSON 日志全覆盖
  4. **删默认密码**：DB/中间件密码改环境变量注入（`${VAR:-dev_only}`），移除 gitleaks 对 docker-compose 的豁免
  5. **上传/SSRF 防护**：`AttachmentController` 扩展名白名单 + 10MB 兜底 + UUID 文件名（防路径穿越）；`OssService` 仅 http/https + 拒绝内网/回环/链路本地 + DNS 重绑定防护
  6. **内容安全 fail-close + 输出检测**：`detectSensitive` 异常返回 `SYSTEM_ERROR` 标签（调用方拒绝）；chat-core 引入 green SDK，`ChatProcessor.checkOutputSafety` 对 LLM 完整答案检测，命中跳过缓存/记忆写入（不阻断已推送流式内容）
- **后果**：✅ 方法级鉴权恢复，时序攻击根除；✅ WebSocket 越权订阅封堵，且不破坏匿名在线人数/监控场景（宽容握手+订阅级鉴权的权衡）；✅ 日志敏感信息遮蔽；✅ 默认密码清零，gitleaks 恢复扫描 docker-compose；✅ 上传/SSRF 攻击面收敛；✅ 内容安全 fail-close，输出侧检测落地；⚠️ 流式输出检测不阻断已推送内容（流式架构固有特性），仅作用于缓存/记忆复用链路；⚠️ 上传扩展名白名单收紧了「无扩展名文件」历史行为（需产品确认）；全量 895 用例全绿。

## ADR-027 弹性伸缩与服务发现：Nacos 动态 upstream + least_conn（2026-08-16）

- **状态**：Accepted（2026-08-16 上线）
- **背景**：web 实例数写死在 Nginx `upstream`（`ip_hash` + 固定 8081/8082），core 双实例靠 web 手动配 `base-urls`；想扩缩容必须改配置 + 手动 reload。且发现 `health-check.sh` 仍守护已废弃的 8082，导致「幽灵实例」（restart 脚本已注释 8082 但 health-check 仍拉起）。
- **决策**：
  1. **Nacos 服务发现正式启用**：实例启动自动注册、心跳超时自动摘除
  2. **动态 upstream 同步器**：`nacos-upstream-sync.sh`（主服务器）周期拉取 Nacos 健康实例 → 生成 `/etc/nginx/conf.d/upstream.chat.conf` → 与旧配置 diff，有变化才 `nginx -s reload`；Nacos 无响应/无健康实例时保留旧配置（不破坏流量）；crontab 每分钟执行
  3. **负载策略 `ip_hash` → `least_conn`**：放弃粘性，改用最小连接数（长连接 WebSocket 更均衡）
  4. **弹性扩缩容控制器**：`web-scale.sh`（scale-up/scale-down/status）+ health-check 标记机制（`web-8082-disabled` 标记存在则跳过守护 8082）
- **后果**：✅ 扩缩容零改配置、约 1 分钟自动生效（Nacos 摘除/注册 + cron 同步）；✅ 幽灵实例问题根除（标记机制接管 8082 守护）；✅ 纯轮询负载更均衡；⚠️ **放弃粘性依赖跨节点广播机制**——结果无论在哪节点生成，`BroadcastService` + RabbitMQ `cross-node` + `_nodeId` 都能推回真正持有用户连接的节点，这是放弃 `ip_hash` 的前提；⚠️ 主服务器 `nacos-upstream-sync.sh` 与 Milvus 服务器 `web-scale.sh`/`health-check.sh` 三处脚本需保持一致（仓库 `scripts/` 为权威源）。

