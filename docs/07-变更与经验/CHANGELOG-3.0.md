# 3.0 版本更新公告

> 发布日期：2026-08-11（初版）· 持续更新至 2026-10-03

---

## 配置管理治理：模板收敛 + 体检工具 + 部署前置卡点（2026-10-03）

> 背景：架构自评中「配置管理」维度最低分（5.5）。此前 LLM 401 连环排障暴露的四个坑（QWEN/QIANWEN 命名漂移、`.env` 重复变量、DB 旧 key、deploy.sh 指错服务器数周）全部源自配置三源（`.env` / Nacos / DB）分裂且无任何一致性校验，本轮系统性修复。

### 1. 命名漂移修复（install-server.sh）

- 问题：`install-server.sh` 生成的服务器 `.env` 模板写 `QIANWEN_API_KEY=changeme`，而代码/Nacos 副本/文档全部读取 `QWEN_API_KEY`——死变量，standalone/DB 故障兜底永远不生效。修复为 `QWEN_API_KEY`，并加注释防再犯（LLM Key 变量名以代码引用为准，勿用拼音别名）。

### 2. .env 模板双源收敛（22 处漂移清零）

- 问题：`install-server.sh` 内嵌的 `.env` heredoc 模板与仓库根 `.env.template` 是两个独立演化的模板，变量集互相缺失达 22 处。
- install 模板补齐：`APP_MASTER_KEY`（自动 `openssl rand -hex 32` 生成——此前新装机器无主密钥、key 明文落库）、`DASHSCOPE_API_KEY`（RAG 向量化必需、不走 DB）、`LLM_API_KEY`、`INTERNAL_API_TOKEN`（自动生成）、`SQL_EXECUTOR_PASSWORD`/`MONITOR_PASSWORD`、RabbitMQ/阿里云内容安全/OSS 凭据、`EMBEDDING_MODEL/BASE_URL`。
- `.env.template` 补齐：Nacos/Milvus/Neo4j/RAG/意图识别等运维段变量，两模板形成同一全集（真相源规则注释同步对齐：DB 为 provider key 唯一真相源，env 仅兜底，DASHSCOPE 例外）。

### 3. 新增配置体检工具 config-check.sh（scripts/）

- 五项检查：① 重复变量定义（后值静默覆盖前值，ERROR）② 死变量（KEY/TOKEN/PASSWORD 类判命名漂移，ERROR）③ 两份模板变量集漂移（ERROR）④ 代码以无默认值 `${VAR}` 引用但模板均未提供（WARN）⑤ 密钥占位值未填（仅对真实 env 报，WARN）。
- 支持远端体检：`bash scripts/config-check.sh --remote root@host` 经 ssh 检查服务器 `/opt/app/.env`，凭据不落地本机。
- 首跑即抓出全部 22 处模板漂移（正是工具要防的问题本身），修复后 0 ERROR / 0 WARN；`NACOS_USERNAME/PASSWORD` 识别为预留变量（Nacos 开鉴权后使用）降级为提示。

### 4. deploy.sh 部署前置卡点

- 任何目标（all/单模块/前端）部署前先执行 `config-check.sh`，存在 ERROR 即中止——配置漂移不再可能流向线上。

### 5. 遗留与教训

- `.env` 兜底 key 与 DB key 一致性无法脚本化比对（AES-256-GCM 密文带随机 IV，每次密文不同）；建议后续在 `LlmProviderAdminService` 60s 定时刷新处解密后与环境变量比对、漂移打 WARN 日志。
- **bash UTF-8 坑**：`set -u` 下 `$v（`（全角括号紧跟变量名）会把全角字符并入变量名、报未定义变量——凡 `$var` 后跟全角字符必须写 `${var}`；macOS BSD grep 不支持 `\s`，须用 `[[:space:]]`。
- Nacos 副本 diff（`docs/nacos-configs/` ↔ 线上 Nacos）与 Nacos 鉴权仍待做。

### 6. 备份策略落地（数据层 P0，同日追加）

- 背景：架构自评「数据层 6.5」最大短板——MySQL(RDS)/Milvus/Redis 全部零备份，一次磁盘故障即不可逆丢失（Milvus 含知识图谱 kg_entity/kg_triple + RAG 向量库）。
- 新增 `scripts/backup.sh`（服务器端）：MySQL `mysqldump --single-transaction` 逻辑全量（RDS 云快照的兜底）+ Milvus 停机快照（stop etcd/minio/standalone → tar volumes → 重启 → healthz 等待，窗口约 1~3 分钟）+ 过期清理（默认保留 7 天）+ 异机复制（`BACKUP_REMOTE_HOST` 指向入口服务器，需 ssh 互信）；落盘自动 `gzip -t` 校验 + 空文件检测；`--install-cron` 自装每日 04:00 定时任务。
- 新增 `scripts/restore.sh`：`list` / `mysql <dump>` / `milvus <tar>` 三个子命令；恢复均为破坏性操作（mysql 整库覆盖、milvus 清空 volumes），内置交互确认 + `--yes` 跳过。
- 集成：`deploy.sh install` 自动上传两脚本，`install-server.sh` 新增 `ensure_backup()` 装定时任务；Redis（缓存/会话）与 RabbitMQ（在途任务）明确不备份并记录理由。
- 运维文档：`docs/03-运维部署/备份与恢复.md`（wiki 同步）——覆盖范围、安装、异机互信配置、恢复步骤、季度演练建议与已知限制（`--online` 一致性风险等）。
- Redis 不备份 ≠ 无损：登录态/验证码缓存会清空，重启即重建，可接受。

### 7. 图谱计数并发丢更新修复（数据层 P0 收官，同日追加）

- 问题：`MilvusKnowledgeGraphService` 的 rel_count/count 自增是「查旧值 → +1 → upsert」读-改-写，非原子——chat-llm **双实例（9095/9096）** 并发抽取同一三元组、或 Redis 去重键不可用时（两实例同时处理同一消息），互相覆盖导致计数丢失（如 5+2 应为 7，实得 6）。
- 修复：Milvus 2.3 无原子自增，新增 `withCounterLock` 计数锁——Redis `SET NX` + TTL 10s 跨实例互斥（随机 token + Lua 比对释放防误解锁），抢锁失败自旋等待（100ms×50），超时 **fail-open**（计数偏差可自愈：同三元组再现仍会 +1，不阻塞抽取线程）；Redis 故障回退进程内 monitor 锁（单实例，当前抽取本身为单线程执行器已串行）。
- 覆盖点：`upsertEntity`（kg_entity.rel_count）与 `upsertTriple`（kg_triple.count）两处读-改-写均包锁，键空间 `kg:cnt:e/t:{指纹}`。
- 验证：新增 4 项锁行为测试——Redis 加锁+释放、Redis 故障回退进程内、200 线程同 key 非原子计数零丢失、自旋超时 fail-open；chat-llm 全量 224 测试 0 失败。

---

## 知识图谱接入 Milvus + 依赖安全补丁 + 生产全量部署（2026-09-29）

### 1. 知识图谱后端 Milvus 化（生产免依赖 Neo4j）

- 新增 `MilvusKnowledgeGraphService`（`app.knowledge-graph.backend=milvus`，**生产默认已切换**），实现 `KnowledgeGraphFacade` + `GraphStore` SPI，与 neo4j/memory 后端同构可插拔，上层（GraphClient/前端图谱页）零改动。
- 双 Collection 设计：`kg_entity`（实体 + 向量 HNSW/COSINE）/ `kg_triple`（三元组），PK=sha256 内容指纹，upsert 天然幂等，重复三元组 count 自增。
- 查询全走 Milvus：`getGraph` 标量 expr；`searchEntities` 向量语义召回 + 一跳邻居（embedding 不可用回退 like）；返回结构与 Neo4j 版完全一致。
- 复用 RAG 管道（milvusServiceClient/LegacyEmbeddingService），均 required=false 安全降级；指纹幂等单测 ×3，chat-llm 全量 220 测试 0 失败。决策详情见 ADR-028。
- 生产服务器 Neo4j 不再部署（内存余量考虑）；`.env` 移除无效遗留变量 `APP_KNOWLEDGE_GRAPH_BACKEND`（实际映射名为 `KNOWLEDGE_GRAPH_BACKEND`）。

### 2. 依赖安全补丁（GitHub Dependabot 全部告警清零）

- 同线属性覆盖升级（不动 Spring Boot 3.1.6 主版本，Spring Cloud Alibaba 兼容性零影响）：snakeyaml 1.33→**2.2**（CVE-2022-1471 高危 RCE）、spring-framework 6.0.14→**6.0.23**（CVE-2024-22243/22259/22262）、tomcat 10.1.16→**10.1.42**（CVE-2024-50379 高危条件竞争 RCE 等）、netty 4.1.101→**4.1.118.Final**（CVE-2024-29025 等）、jackson 2.15.3→**2.15.4**（CVE-2024-25710）。
- 全模块构建 + 测试通过，产物内版本逐一验证；修复升级引入的 2 个过时测试断言（DebateFlow/RestTemplateConfig）。

### 3. 生产环境加固与全量部署

- **Milvus 崩溃根因修复**：standalone 首启时 etcd 未就绪导致凭据写入超时 panic（非 OOM）；三容器补 `restart: always`，重启后 healthz OK。
- **服务器加 2G swap**（vm.swappiness=10）：7.4G 内存在 8 Java 进程 + 4 容器下长期 ~85%，swap 作峰值安全垫。
- **deploy.sh 路径修复**：jar 上传路径 `/opt/app/chat-xxx` 与服务器实际目录 `/opt/app/{core,web,games,media,llm}` 不一致导致 scp 失败，已对齐。
- **全量部署完成**（165s）：前端 + 双 core + 双 web + games + media + 双 llm 全健康；chat-llm 日志确认 `[MilvusKG] Milvus 知识图谱后端已启用` + StorageRegistry 注册 `type=graph impl=milvus`。
- 服务器新装：Docker 24（含国内镜像加速）、Nacos v2.2.3 容器（当前部署未启用，备用）、Milvus v2.3.4 三件套。

---

## 安全修复三连 + 启动自检 + cross-node 根治（2026-09-27）

### 1. WebSocket 订阅级鉴权从空实现到真正落地（越权漏洞修复）

- 问题：`WebSocketConfig#configureClientInboundChannel` 此前是空实现（注释称 SockJS 场景下取不到 session attributes 而暂缓），任意建立 WS 连接的客户端（含匿名连接）都可订阅他人私有 topic（`/topic/user.{id}`、`/topic/debate.{id}`、`/topic/treehole.{id}`），**越权接收他人私聊/树洞的流式回答**。
- 根因：此前误判「SockJS + STOMP 取不到握手 attributes」；实际握手拦截器写入的 attributes 会经 `SESSION_ATTRIBUTES` 头随 STOMP 帧透传，SUBSCRIBE 帧上稳定可取（与 Spring Security WebSocket 拦截器同一机制）。
- 修复：`ChannelInterceptor` 拦截 SUBSCRIBE 帧，私有 topic（`^/topic/[a-z]+\\.{\\d+}$`）必须「已登录 且 token 归属 userId == topic 中的 userId」，否则抛 `MessagingException` 拒绝（fail-close，属性缺失也拒绝）。公开 topic（online-count、public-questions、castlesiege.state 等）不受影响。
- 验证：新增 `WebSocketSubscriptionAuthTest` 6 项断言（本人订阅放行 / 他人订阅拒绝 / 匿名订阅私有拒绝 / 匿名订阅公开放行 / 会话属性缺失 fail-close），全绿。

### 2. /internal/** 共享令牌鉴权（内部接口双层防护）

- 问题：web→core 的内部接口（含 `/internal/tools` 管理写接口）**零鉴权**，纯靠「Nginx 不转发」的网络隔离；一旦端口映射/防火墙配置失误即直接暴露公网。
- 修复：新增 `InternalApiTokenFilter`（`MessageDigest.isEqual` 常量时间比较，防时序侧信道）校验请求头 `X-Internal-Token`；chat-web 侧 `RestTemplateConfig` 拦截器对 `/internal/**` 请求**自动注入令牌**（CoreClient 业务代码零改动）。令牌未配置时放行并 WARN 一次（本地开发兼容）。
- 配置：环境变量 `INTERNAL_API_TOKEN`（web 与 core 同值，`openssl rand -hex 24` 生成）已写入 `/opt/app/.env`。
- 验证：线上三态实测——无 token **401** / 正确 token **200** / 错误 token **401**；web→core 调用链路无拒绝记录。

### 3. 客户端 IP 防伪造（限流/登录锁定绕过修复）

- 问题：`IpRateLimitInterceptor` 与 `AuthService` 无条件采信 `X-Forwarded-For` / `X-Real-IP`，攻击者直连后端端口可逐次伪造新「IP」，**绕过登录失败锁定（5 次锁 15 分钟）、注册限流与全站限流/黑名单**。
- 修复：新增 `ClientIpResolver`——仅当 TCP 直连方是**可信代理**（回环/RFC1918 内网，即生产 Nginx 同机/同内网）时才采信代理头（X-Forwarded-For 优先取首段，与原语义一致）；外部直连一律取 `RemoteAddr`，忽略一切可伪造头。限流拦截器、登录锁定两处调用方统一委托该工具。
- 验证：新增 `ClientIpResolverTest` 7 项断言（伪造忽略 / 可信采信 / 回退 / unknown 处理 / 网段判定），全绿；线上 curl 无 UA 请求被过滤器拦截顺带验证限流链路在工作。

### 4. CORS 白名单占位符清理

- 问题：`SecurityConfig` 白名单残留 `your-nginx-ip` / `*.your-domain.com` 占位符，实际不匹配任何来源，形同虚设。
- 修复：改为真实生产地址（服务器 IP + 域名 `yangsy.online`）。

### 5. 启动配置自检 StartupConfigValidator（防「带病上线」）

- 问题：历史三次线上事故均属「启动时即可检出」类——LLM api_key 占位符 → 调用 401；`tree_hole_messages` 缺列 → Unknown column；cross-node 交换机未声明 → 每次广播刷 channel error。都是等到线上报错才发现。
- 修复：应用就绪后自检（**只告警不阻断**，避免误判导致无法拉起）：① 关键环境变量（DB/RabbitMQ/JWT/INTERNAL_API_TOKEN）占位符检测；② 已启用 LLM 供应商的 api_key 空/占位符检测（附修复 SQL）；③ `tree_hole_messages` schema 漂移检测（provider/model/mood/tokens 四列，附修复 DDL）。ERROR 级别日志可被告警系统采集，日志关键字 `[StartupCheck]`。
- 验证：线上运行输出「配置自检通过」（112 库 schema 实测完好，provider/model/tokens 列都在）。

### 6. cross-node 交换机无条件声明（channel error 根治）

- 问题：`BroadcastService` 只要存在 RabbitTemplate 就向 `cross-node` 交换机发布（双 web 实例 STOMP 广播同步），但该交换机**仅在 `app.cross-node.enabled=true` 时**由 `CrossNodeConfig` 声明；单机部署（开关关闭）时交换机不存在，每次广播触发 RabbitMQ channel error 刷 ERROR 日志，需人工上 broker 补建。
- 修复：新增 `CrossNodeExchangeDefaults` **无条件声明**交换机（幂等，参数与 CrossNodeConfig 完全一致，开关开启时两边声明等价不冲突）。
- 验证：部署后日志确认 cross-node channel error 归零，`rabbitmqctl` 确认交换机自动存在。

### 7. 部署踩坑记录

- **前端 nginx 映射必须匹配 `base: '/chat/'`（白屏事故）**：前端构建 `base` 是 `/chat/`（index.html 引用 `/chat/assets/xxx.js`），而主服务器 `conf.d/chat.conf` 是按 `base:'/'` 旧构建写的 `root /opt/app/static/chat`——新构建部署后 `/chat/assets/*` 被映射到不存在的 `chat/chat/assets/` → **全站 JS/CSS 404 白屏**（旧构建能用纯粹是因为当时引用 `/assets/*` 恰好能被该 root 命中）。修复：两台服务器 `chat.conf` 的 `root` 统一改为 `/opt/app/static`（与仓库 `nginx.conf` 8080 server 语义一致），SPA 兜底 `try_files $uri $uri/ /chat/index.html`，html 一律 `no-cache`、指纹资源 `immutable`。**教训：改前端 base/构建配置前先核对线上 nginx 的 location 映射**。
- **前端必须发到「入口服务器」（2026-09-27 追加，本次最深的坑）**：生产是双服务器拓扑——`yangsy.online`（112.124.106.108，低配 nginx 入口，自持静态文件 + API 反代到应用服务器）+ 应用服务器（47.110.234.58，全部 Java 服务）。此前几轮前端一直 scp 到应用服务器，用户经域名访问的始终是入口服务器的**旧版**——「改了不发版」的假象由此而来。**前端发布目标 = 主服务器 `/opt/app/static/chat/`（密钥 `ngnix.pem`）；后端发布目标 = 应用服务器（密钥 `Core.pem`）**。两台 nginx 均已给 `index.html` 加 `Cache-Control: no-cache`（入口页回源校验 + hash 资源 30d immutable），发版后普通刷新即可见。
- **deploy.sh 与服务器目录不符**：脚本写 `/opt/app/chat-core/`，实际布局是 `/opt/app/core/`（无 `chat-` 前缀），`deploy.sh all` 会在 scp 失败处静默中断——本次改为手动 scp+restart 部署，脚本待修。
- **Spring Boot 只加载 classpath 第一个 application.yml**：chat-common 里新增的 `app.security.internal-token` 配置项被各模块自己的 application.yml 覆盖，导致令牌首次部署未生效（401 没拦住）；解法：`@Value` 用**嵌套占位符** `${app.security.internal-token:${INTERNAL_API_TOKEN:}}` 直接兜底环境变量，不依赖 yml 映射。
- **既有测试失败 2 个**（`DebateTreeProcessorTest$Decompose`）：HEAD 干净工作区验证与本次改动无关——树状辩论固定 3 视角后旧测试仍断言动态数量，待按现行为更新测试。

### 8. 代码收敛

- `OpenAICompatProvider` / `OpenAISdkProvider` 各持一份逐字相同的 `toInt` 重复实现（含相同的空 catch 回退），收敛到 `UsageJsonUtils`。
- 评审修正：`rag/legacy` **不是死代码**——`HybridSearchService`、`KnowledgeController`、`LegacyRagController` 均在引用，不可直接删除，真正的清理需先做调用方迁移。

### 9. LLM api_key DB 落库加密（深夜追加，明文密钥根除）

- 问题：全部大模型 api_key **明文**存 `llm_provider_props` 表——拖走一个数据库备份即拿走 DeepSeek/千问/豆包等全部密钥。评审定位为当前最大遗留高危项。
- 修复：新增 `ApiKeyCipher`（AES-256-GCM，密文 `enc:v1:<base64(IV+密文)>`，SHA-256 从 `APP_MASTER_KEY` 派生密钥，随机 IV，GCM 防篡改）；写侧（chat-llm 管理面）落库前加密，「留空保留原值」不二次加密；读侧双链统一解密（chat-llm `propsMap` + chat-core/web/media 的 `CachedModelConfigRepository`，7 个调用方零改动）；**chat-llm 启动自动迁移存量明文**（幂等）。
- 兼容策略：无前缀明文原样透传（渐进迁移）；主密钥未配置时写侧明文落库 + WARN（本地开发兼容）；密文存在但主密钥缺失/不一致 fail-fast 抛异常，不静默用错误 key。
- 部署踩坑：chat-llm 主类是显式 `@Import` 注册公共组件（不扫 `com.example.chat` 包），新 `@Component` 必须加进 `LlmApplication` 的 `@Import` 列表——首启因缺 `MasterKeyProvider` 注册失败，补 `@Import` 后恢复。
- 验证：① 单测 `ApiKeyCipherTest` 8 项全绿；② chat-llm 217 测试全绿；③ 线上启动后 DB 实查 4 个 api_key 全部为 `enc:v1:` 密文（deepseek/qwen/doubao/openai）；④ llm 3 提供商注册成功、core 6 模型缓存刷新 0 失败；⑤ **E2E 真实调用 deepseek-chat 返回成功**（密文→解密→上游 200，838ms）。
- 注意：`APP_MASTER_KEY` 丢失/变更 = DB 全部 api_key 不可解密，需重录；服务器 `.env` 已生成配置。

### 10. 遗留测试修复

- `DebateTreeProcessorTest` 2 个失败断言按「固定 3 视角」新行为更新（LLM 返回 2 视角断言降级为默认 3 视角；markdown 样例改为 3 视角正常解析路径）。chat-core 全绿，至此全模块测试 0 失败。

---

## 树状博弈固定 3 视角 + games 服务修复 + 功能精简（2026-08-17 深夜）

### 1. 树状博弈固定 3 视角

- 问题：树状博弈有时只显示 2 棵树，而非 3 棵。
- 根因：语义拆解 prompt 写「2-3 个视角」，LLM 常偷懒返回 2 个；而校验只兜底「空/超 4 个」，未兜底「不足 3 个」。
- 修复：prompt 改为「恰好 3 个」+ 校验 `size()<3` 时用默认 3 视角。

### 2. games 服务领主排行榜修复

- 问题：城池攻防战领主排行榜「登录了也没有」——接口 HTTP 超时无响应。
- 根因：`chat-common` 的 mysql-connector 为 `<scope>runtime</scope>` 不传递，games fat jar 缺 MySQL 驱动，HikariCP 报 `NoClassDefFoundError: ExportControlled`，整个 games 服务接口卡死。
- 修复：`chat-games/pom.xml` 显式声明 mysql-connector-j；领主排行榜加入 SecurityConfig 白名单（游客可看，上榜需登录）。

### 3. 功能精简（暂隐藏）

- **AI 群聊伙伴**、**3D 模型生成** 两个入口从首页功能卡片 + 桌面端/手机端导航栏暂时隐藏（路由与功能代码保留，未删除）。
- 目的：精简首页与导航，聚焦核心功能。

### 4. 文案优化

- 知识脉络图说明精简为一行通俗文案：「查询你想了解的知识，以及它们之间的关联信息」。
- 首页去掉「制作者：杨思义」署名。

---

## 稳定版发布：深度思考可配置化 + 豆包换 mini + 视角并行（2026-08-17 晚）

### 1. 深度思考可配置化

- 个人对话、AI 群聊、观点辩论均新增「🧠 深度思考」开关，默认普通模式（快速响应）。
- 全链路透传 `deep_thinking`：前端 → web（MessageController/DebateController）→ core（ChatProcessor/DebateProcessor/DebateTreeProcessor）→ `LLMInvoker`（ThreadLocal 上下文）。
- 仅对豆包 seed-2.0 系列（原生 reasoning）生效：关闭 → `thinking:disabled`（快），开启 → `thinking:enabled`（深入但慢）。

### 2. 豆包换 mini 模型（辩论提速）

- 根因：DB `llm_model_config` 表的 doubao 模型仍是 lite，chat-llm 定时刷新（60s）会覆盖 Nacos 的 mini 配置，导致「改了 mini 但实际还是 lite」。
- 修复：DB 模型表 lite → mini（`doubao-seed-2-0-mini-260215`），与 Nacos 一致。
- 实测：mini 关闭思考后首 token 0.6~1.0 秒（vs lite 2.2 秒，pro 更慢）。

### 3. 树状辩论视角真正并行

- 根因：`DebateTreeProcessor` 的 `batchPool` 核心线程数 = 2，而树状辩论拆解 3 个视角。`ThreadPoolExecutor` 在队列未满时不会扩容，导致第 3 个视角排队串行。
- 修复：核心线程数 2 → 4，3 个视角真正同时并行。

---

## 个人对话性能优化 + 安全加固 + 包结构重构 + 内存治理（2026-08-17）

### 1. 个人对话「慢 + 非流式」修复（工具探测按意图触发）

**问题**：个人对话每次（含纯闲聊）都先同步做一次「工具探测」LLM 调用（实测 4~5 秒）判断是否用工具，再走流式输出。这导致：① 首 token 被推迟 4~5 秒（慢）；② 用户感知「卡一下才全出来」（非流式）。

**修复**：利用已有的意图识别结果，仅 `TASK_EXECUTION`（任务执行，计算/天气/搜索等）意图才走工具探测；闲聊/知识问答/写作等意图直接流式，首 token 立即开始。语义正确——工具本就服务于「任务执行」。

### 2. 响应慢根因修复（11 秒 → 1-3 秒）

- **tool_call_id 缺失 Bug**：`ToolDispatcher` 构造 tool 消息漏传 `tool_call_id`，DeepSeek 严格校验报 400 → 重试/熔断连锁（单次工具调度耗 11.5 秒）。修复：`LlmToolInvoker.toolCallIdOf()` + 补传字段。
- **豆包深度思考**：`doubao-seed-2-0` 系列默认开启 reasoning，每次生成大量思考 token（实测 149 个）导致首 token 4~18 秒。修复：`LLMInvoker` 对 doubao 透传 `thinking:{type:disabled}`，首 token 稳定 ~2.2 秒。

### 3. 安全加固

- `FeatureFlagController` 写操作（toggle/setPercentage/addWhitelist/clearCache）加管理员密码鉴权（`X-Admin-Password`），原任意登录用户可篡改特性开关。
- `SqlExecutorController` 写语句白名单（仅 UPDATE/INSERT）+ UPDATE 强制 WHERE，防止全表误更新。
- 内容安全抽象为「两道防线」：通用闸门（本地词库 + 阿里云 fail-close，chat-common）+ 大模型自检（`GuardrailProvider` SPI，chat-llm）。

### 4. 包结构重构（chat-llm）

- 消除 `llm.llm` 冗余命名：`llm.llm.routing` → `llm.routing`，`llm.llm.routing.db` → `llm.routing.db`。
- `LlmProviderAdminController` 从 `routing.db` 移到 `controller` 包。

### 5. 内存治理（可用内存 9.6% → 15%）

- 根因：Java 进程缺 `-XX:MaxDirectMemorySize`，堆外 Direct Buffer 失控。
- 修复：5 个 restart 脚本统一加 `-XX:MaxDirectMemorySize` + `-XX:MaxMetaspaceSize`，可用内存 715MB → 1165MB。
- 内存告警阈值 8% → 20% 提前预警。

### 6. 其他

- `LLMCallException` 全局处理器（区分上游 4xx/5xx，清晰错误语义）。
- `MILVUS_HOST` 从模糊的 `172.23.172.14` 改为明确的 `127.0.0.1`。
- springdoc 2.5.0 → 2.3.0 版本对齐。
- **grpc-server 保持 2.15.0 不升级**：3.1.0 需 micrometer 1.12+，与项目锁定的 1.11.6 冲突（启动报 `NoSuchMethodError`），已在 pom 注释记录原因。

---

## 图引擎健壮性修复 + Key 收敛 + 缓存原子性 + 关键链路测试 + 压测一键化（2026-08-16）

### 1. LangGraph 自研图引擎（`GraphExecuteService`）四硬伤修复

| 项 | 问题 | 修复 |
|----|------|------|
| 流式线程安全 | `invokeStreamAndJoin` 用 `StringBuilder` 假设 `invokeStream` 同步阻塞返回即完成，底层换成真异步 SSE 会数据竞争 | `StringBuffer` + `CompletableFuture` 显式等待 `onComplete`/`onError`，120s 超时兜底 |
| 分支并发写 state | 并行分支共享 `branchState` 快照（一写多读乐观假设），分支内逻辑节点写入会并发写 HashMap | 每分支独立 `new HashMap<>(branchState)` 局部副本，彻底隔离 |
| 条件路由表达力弱 | `evaluateCondition` 仅支持 `contains`/`equals`，无法数值比较、无法引用 state 变量 | 新增 `gt/gte/lt/lte/ne/eq` 数值比较 + `{{state.xxx}}`/`{{state.__output}}` 模板变量，两操作数均可引用变量 |
| 无环检测 + O(n) 查找 | `findNode` 逐次线性扫描，环只能靠 maxSteps 硬截断 | `buildNodeIndex` 建 `nodeId→GraphNode` 索引 O(1) 查找 + 环告警（不终止，保留 maxSteps 兜底，不误杀合法有限循环图） |

### 2. Key 解析收敛（消除 DB + .env 双源规则漂移）

新增 `ApiKeyResolver` 公共工具类（`chat-common`），收敛 7 处重复的「`apiKeyEncrypted` 优先、环境变量兜底」判断（`LLMInvoker`/`LLMClient`/`TreeHoleService`/`ModelAutoChatService`/`ToolDispatcher`/`SubAgentWorker`/`TripleExtractionService`），与 `BaseUrlResolver` 形成对称治理。

### 3. 缓存原子性修复

`CachedModelConfigRepository` 的 `byTypeCache` 由 `clear()+putAll()` 改为 `volatile` 引用替换，消除「新 enabledCache + 旧 byTypeCache」的瞬间不一致，兑现「原子替换」承诺。

### 4. 关键链路测试补齐

- `ApiKeyResolverTest`（6 例）+ `CachedModelConfigRepositoryTest`（8 例）：key 解析规则、缓存启动加载/读缓存/刷新失败保留旧缓存/写操作触发刷新
- `LlmProviderAdminServiceTest`（6 例）：DB 覆盖 YAML、定时刷新、刷新失败保留旧路由、跳过禁用/缺 key、apiKey 不回传
- `GraphExecuteServiceTest` 新增 6 例：环告警、数值条件路由、state 变量、分支隔离、异步流式等待

### 5. 压测一键化

新增 `stress-test/run-benchmark.sh` 封装 `baseline`/`stress`/`soak` 三场景，结果带时间戳落盘到 `results/`（已 gitignore）可对比基线；`k6-http-test.js` 支持 `K6_STAGES` 环境变量覆盖并发曲线。

---

## 安全加固 + 弹性伸缩 + 在线人数真实统计（2026-08-16）

### 1. 安全加固（6 项）

| 项 | 内容 |
|----|------|
| 方法级鉴权 | 恢复 `@EnableMethodSecurity(prePostEnabled=true)`，管理密码改 `MessageDigest.isEqual` 常量时间比较，消除时序侧信道 |
| WebSocket 双层鉴权 | 握手验 JWT（宽容匿名）+ 私有 topic 订阅级鉴权（`ChannelInterceptor`，防横向越权订阅他人消息） |
| 日志脱敏 | 新增 `MaskingMessageConverter`（logback 自定义 converter），`%msg` → `%maskedMsg`，遮蔽 JWT/签名 URL/敏感键值对，明文+JSON 日志全覆盖 |
| 删默认密码 | DB/中间件密码改环境变量注入（`${VAR:-dev_only}`），移除 gitleaks 对 docker-compose 的豁免，Nacos prod 配置明文默认值清空（`${VAR:}` 只从服务器 .env 注入） |
| 上传/SSRF 防护 | `AttachmentController` 扩展名白名单 + 10MB 兜底 + UUID 文件名；`OssService` 仅 http/https + 拒绝内网/回环/链路本地 + DNS 重绑定防护 |
| 内容安全 fail-close + 输出检测 | `detectSensitive` 异常返回 `SYSTEM_ERROR` 拒绝放行；chat-core 引入 green SDK，`ChatProcessor.checkOutputSafety` 对 LLM 完整答案检测，命中跳过缓存/记忆写入 |

### 2. 依赖漏洞清零

- 后端：`mysql-connector-j` 8.0.33→8.2.0、`poi-ooxml` 5.2.5→5.4.0
- 前端：`react-router-dom` 6→7.18.2、`vite` 5→6.4.3、`vitest` 2→3.2.7、`esbuild`→0.25.12、`nanoid`→3.3.18
- Dependabot 告警 12 项全部清零（含 1 critical / 2 high）

### 3. Web 弹性伸缩 + 动态负载均衡

- `nacos-upstream-sync.sh`：周期拉取 Nacos 健康实例 → 动态生成 Nginx upstream → 有变化才 reload
- `web-scale.sh`：一键 scale-up/scale-down 扩缩容
- 负载策略 `ip_hash` → `least_conn`（依赖跨节点广播兜底，放弃粘性换取动态扩容能力）

### 4. 在线人数真实统计

- `WebSocketSessionTracker` 直接统计真实 WebSocket 连接数并广播（60s 刷新）
- `MonitorController.getTotalUsage` 累计使用量改为「已完成且有答案的对话消息数」（`countAllWithAnswers`），替代在线人数快照累加

验证：全量 **895 用例全绿**（chat-common 277 / chat-core 257 / chat-web 93 / chat-llm 198 / chat-games 44 / chat-media 26）。

---

## 〇〇〇〇〇〇〇〇、Multi-Agent 并行工作流可靠性修复：部分分发失败回滚 + 收敛锁 TTL 对齐（2026-08-15）

代码走查发现 Multi-Agent 并行工作流存在两处可靠性隐患，已修复并补测试：

| 项 | 内容 |
|----|------|
| P1-1 部分任务分发失败回滚 | `AgentWorkflowOrchestrator.startWorkflow` 逐条 `sendTask`，中途失败时此前仅 catch 返回 false，导致已写入 Redis 的 plan 状态残留：部分任务已发出却永远凑不齐 `total`（Reconciler 对 `received < total` 直接跳过），plan 卡满 30min TTL、已发子任务白执行、permit 泄漏。修复为失败时 `rollbackPlan()` 清理该 plan 全部 Redis key（plan/meta/total/received/result/lock/converged）并从 Reconciler ZSet 移除；回滚后已发子任务的回传结果因 total key 缺失被 ResultCollector 忽略（total=0 不触发收敛） |
| P1-2 收敛锁 TTL 对齐 | 正常触发路径（`SubTaskResultCollector`）收敛锁 TTL 原为 2min，而收敛要调 LLM 流式总结可能超时，锁过期后 Reconciler 下轮误判"卡住"重复触发 → 双重收敛。修复为抽出共享常量 `AgentWorkflowOrchestrator.CONVERGE_LOCK_TTL = 5min`，正常路径与 Reconciler 共用，消除锁过期窗口 |
| 测试补齐 | 新增 `AgentWorkflowOrchestratorTest`（4 例：部分失败回滚/permit 释放/过载降级/计划生成失败）+ `SubTaskResultCollectorTest`（4 例：共享锁 TTL 收敛/未到齐不收敛/回滚后孤立结果不收敛/异常 nack requeue），chat-core 249 → **257 例全绿** |
| 既有 flaky 修复 | `CircuitBreakerTest.state_isIsolatedPerProvider` 因与 `halfOpen` 测试共享 1ms 极短冷却而时序敏感（5 次失败后 allowRequest 已转 HALF_OPEN 放行探测），改为独立 1s 冷却 registry，8/8 稳定全绿 |

验证：全量 **892 用例全绿**（chat-common 277 / chat-core 257 / chat-web 90 / chat-llm 198 / chat-games 44 / chat-media 26）。

---

## 〇〇〇〇〇〇〇〇、性能与稳定性加固：缓存 Key 双写修复 + 流式 Token 合并广播 + Resilience4j 熔断 + 连接池扩容（2026-08-15）

### 1. 背景

压测与代码走查发现多处性能/稳定性隐患：对话答案缓存因**读写 key 不一致**完全失效（每次请求都穿透打 LLM）；流式回答**每个 token 一次 MQ 同步广播**，双 core 实例下消息量巨大；自研熔断器仅"连续失败 5 次"无法识别慢请求与偶发抖动；`RuleBasedMatcher`/`DebateTreeProcessor` 存在无界增长/OOM 风险；SQL 危险词正则误伤 `created_at` 等字段。

### 2. 变更内容

| 项 | 内容 |
|----|------|
| 缓存 Key 双写修复 | `ChatCacheManager.save` 原仅写**模型级 key**（`sha256(question::provider::model)`），而 `hitAndServe` 读**问题级 key**（`sha256(question::model-pool)`）→ 永不命中。修复为**双写两个 key**：问题级供命中（所有模型共用）+ 模型级保留区分能力，缓存从"永远失效"恢复为真实命中 |
| 流式 Token 合并广播 | 新增 `StreamTokenBatcher`：token 按 **30ms 窗口 / 256 字符阈值**合并后广播一次，流式结束 `close()` 冲刷；广播在锁外执行不阻塞 LLM 流式线程。MQ 消息量降一个数量级，前端延迟 ≤30ms 无感知 |
| Resilience4j 熔断 | 自研"连续失败 5 次"替换为 **Resilience4j 滑动窗口失败率**：窗口 10 次 / 最少 5 次 / 失败率 50% 熔断 / 冷却 30s / 半开放放行 3 次，指标自动上报 Prometheus |
| 消费可靠性 | `ChatRequestConsumer` 改 MANUAL ack + `basicNack`（requeue=false），失败消息进死信队列重试（DLX 指数退避），杜绝"消费者重启即丢消息" |
| 连接池扩容 | HikariCP `maximum-pool-size`：chat-common 10→20、chat-llm 10→20；nacos 配置同步（chat-common-prod 20 / chat-core-prod 30） |
| RabbitConfig 兼容 | Spring AMQP 3.0 移除 `QueueProperties`，改用 `QueueInformation`（`rabbitAdmin.getQueueInfo()` + `getMessageCount()`） |
| 内存防护 | `RuleBasedMatcher` 状态机条目带最后活跃时间，**30 分钟 TTL 定时清理**过期状态；`DebateTreeProcessor` 无界队列 → `ThreadPoolFactory` 有界队列（`BoundedQueue` + CallerRuns），批量池类级复用不再每请求新建 |
| SQL 危险词正则 | `SqlExecutorController` 危险词匹配改**词边界正则**（`\b`），不再误伤 `created_at` 等含子串的字段 |
| 压测脚本 | `stress-test/k6-http-test.js` 增加 `setup()` 单次登录共享 JWT（规避敏感接口 10 次/分钟 IP 限流），带鉴权打核心读接口 + 按 `SEND_RATIO` 触发 AI 链路 |

### 3. 数据库

`docs/db-migrations/V1.2.0__add_reqid_unique_and_fulltext_indexes.sql`（**2026-08-15 已执行完成**）：

- `messages.req_id` 唯一索引（幂等，线上已存在 `uk_req_id`，脚本幂等跳过）
- `messages` 与 `tree_hole_messages` 两表 ngram 全文索引 `idx_ft_question_ngram`（MySQL 8.0+，中文二元分词；列名 `question`）

### 4. 验证

- chat-core 全量测试：**249 用例全绿**（含新增 `ChatCacheManagerTest.save_writesTwoDistinctKeys` 双 key 断言；`CircuitBreakerTest` 重写为滑动窗口语义 8/8）
- 修复过程中发现并修正：`ChatCacheManagerTest.save_writesCache` 原断言单次写入，双写后改为 `times(2)` + ArgumentCaptor 验证两 key 不同

### 5. 说明

- 缓存双写不引入新 key 前缀冲突：问题级 `question:{sha256(question::model-pool)}` 与模型级 `question:{sha256(question::provider::model)}` 哈希不同源
- 熔断器外部 API 不变（`LLMInvoker` 无感），指标名沿用 Prometheus 现有采集
- 部署需重打包 chat-common + chat-core（`mvn clean install -DskipTests`），重启 core 双实例（9090/9092）

---

## 〇〇〇〇〇〇〇、观点辩论场第二轮提速：Reflection 事件可见化 + 提示词增强 + Redis 外存记忆（2026-08-15）

### 1. 背景

用户反馈观点辩论"第二轮启动比较慢"：每轮辩论 = `debate`(3 分支并行 LLM) + `reflect`(3 分支并行 LLM) 共 6 次 LLM 串行调用，但 reflect 节点事件此前全部落入 `default → ignore` 不广播，前端在 `round_end` 后长时间无任何反馈，形成静默期（几秒~几十秒），造成"第二轮启动慢"的体验。

### 2. 变更内容（三层优化）

| 层级 | 内容 |
|------|------|
| P0 提示词增强 | debate 分支 prompt 注入「对方上一轮反思立场」`{{state.conReflections[-1]}}`（反方/中立类推），reflect 分支新增"对方立场是否影响我的判断"自我审视；仅取 `[-1]` 负索引、窗口定长 ≤200 字，**零 token 增长** |
| P1 Redis 外存记忆 | 新增 `debate:memory:{userId}:{topicHash}` JSON 定长窗口（每方仅存最后一条反思立场 + rounds + updatedAt），TTL 7 天；辩论结束 `saveHistoryMemory` 落库、启动时 `loadHistorySummary` 注入 `historySummary`——**同话题二次辩论首轮即带历史立场**；Redis 未配置时静默降级不 NPE |
| P2 reflecting 事件 | reflect 节点 `branch_start`/`branch_end` 事件广播 `reflecting` WS 事件，前端展示「模型正在批判性反思上一轮观点，修正各自立场...」提示，消除静默期 |

### 3. 实现

| 文件 | 改动 |
|------|------|
| `DebateGraphService.java` | 新增 `RedisTemplate`/`ObjectMapper` 注入（`@Autowired(required = false)`）、`loadHistorySummary`/`saveHistoryMemory`/`appendMemory`/`last`/`memoryKey` 工具方法；`buildGraph` 增加 8 参重载注入 `historySummary`；debate/reflect prompt 引用 `{{state.conReflections[-1]}}` 与 `{{state.historySummary}}` |
| `frontend/src/pages/Debate.jsx` | 新增 `reflecting` state，`reflecting` 事件时展示提示块，`round_start`/`synthesizing`/`done`/`error` 时重置 |
| `frontend/src/i18n/translations.js` | 新增 `debate.reflecting` 中英文案 |
| `frontend/src/styles/debate.css` | 新增 `.debate-reflecting` 样式（蓝紫色提示块 + 动画） |

### 4. 验证与部署

- chat-core 全量测试：**248 用例全绿**（含新增 `buildGraph_withHistoryMemory_injectsSummaryAndReflections`、`execute_savesHistoryMemoryToRedis`）
- 测试日志确认 `key=debate:memory:4:11885b rounds=1` 落库
- 前端 `npm run build` 通过
- 生产部署：core 9090/9092 重启健康（`/actuator/health` UP），Redis 7.2.5 连接正常（记忆功能可用）

### 5. 说明

- 同话题二次辩论命中 `debate:memory:{userId}:{topicHash}` → 首轮即注入历史立场，实现"跨会话记忆"（与个人对话历史记忆不同，此为辩论立场级记忆）
- 提示词窗口定长：仅取最后一条反思立场，不随轮次膨胀
- reflect 事件属增强提示，不改变 `round_start → round_response → round_end` 主流程

---

## 〇〇〇〇〇〇、RAG 能力升级：联网搜索 + 语义重排 + 引文溯源 + BM25 混合检索（2026-08-15）

### 1. 背景

对标 DeepSeek 等头部产品，补齐标准 RAG 分层方案的关键能力：检索结果经重排精排、向量检索与 BM25 关键词混合召回、回答附引文页码溯源，并支持 LLM 联网搜索。四项能力全部实现，并默认提供**逐级降级**保障——任一环节未启用或失败自动退回纯向量检索，不阻塞主链路。

### 2. 变更内容

| 能力 | 实现 | 默认 | 开启方式 |
|------|------|------|---------|
| 联网搜索 | chat-core 新增 `WebSearchTool`（`web_search`，DuckDuckGo 免 key / SerpAPI）、`WebFetchTool`（`web_fetch`，剥标签抓正文），复用既有 Tool/Function Calling 链路 | ✅ 开 | 无需配置 |
| 语义重排 Rerank | chat-llm 新增 `RerankService`，支持 jina / local / none，失败降级原序 | 关 | `RERANK_ENABLED=true` + `RERANK_API_KEY`（Jina） |
| 引文溯源 | `page` 字段贯穿 PDF 逐物理页解析 → 分片 → 向量库/内存 → 检索结果 → prompt，展示"来源 xxx 第 N 页" | ✅ 开 | 新上传 PDF 自动带页码 |
| BM25 混合检索 | chat-llm 新增 `HybridSearchService`：向量召回(×3) ∪ MySQL ngram FULLTEXT → RRF(rrf-k=60) → Rerank → topK | 关 | `HYBRID_KEYWORD_ENABLED=true`（启动自动幂等建 `rag_chunks` 表） |

### 3. 关键设计

- **降级编排**：`HybridSearchService.search()` 任一环失败（含未启用）逐级降级，最终退回 `LegacyVectorStoreService` 纯向量；`LegacyRagController` 统一入口 `retrieve()` 兜底
- **BM25 方案**：`rag_chunks` 表 + ngram `FULLTEXT` 索引（MySQL 5.7.6+/8.0 中文二元分词），`MATCH...AGAINST` 召回 + sigmoid 归一化；按 kb/doc 删除联动
- **Rerank 模型**：默认 `jina-reranker-v2-base-multilingual`，支持 jina/local/none 三档
- **引文格式**：prompt 上下文输出 `--- 来源: {source} 第{page}页 (相似度: 0.xx) ---`
- **兼容旧数据**：Milvus 旧 chunk 无 `page` 字段，读取时缺字段置 0，不报错

### 4. 验证与部署

- `mvn test-compile` + checkstyle 全绿；`DocumentParserTest` / `TextChunkerTest` / `LegacyEmbeddingServiceTest` 通过
- 重新打包上传 `chat-core`（94M）/ `chat-llm`（119M）jar，重启双实例
- 9090 / 9092 / 9095 / 9096 `/actuator/health` 全部 UP（MySQL/Nacos 正常）
- core 日志确认 `web_search` / `web_fetch` 注册（共 6 个工具启用）
- 生产默认：联网搜索开、Rerank/BM25 关；启用需在服务器 `.env` 配 `RERANK_ENABLED` / `HYBRID_KEYWORD_ENABLED` 后重启 llm

---

## 〇〇〇〇〇、前端全站中英文翻译（i18n）上线（2026-08-15）

### 1. 背景

产品面向多语言用户，此前前端文案全部硬编码中文。本次上线全站 i18n：首页、聊天、辩论、树洞、私聊、管理后台、游戏等全部页面支持一键中英文切换，覆盖 600+ 词条。

### 2. 变更内容

| 项 | 内容 |
|----|------|
| i18n 基础设施 | 新增 `frontend/src/i18n/`：`LanguageContext.jsx`（`useLanguage()` → `{ t, lang, toggle }`，`t(key, vars)` 支持 `{var}` 插值，fallback 返回 key）+ `translations.js`（扁平键 `'namespace.key': '文案'`，zh/en 各 616 键完全对称） |
| 全站接入 | 首页、聊天、个人对话、辩论、树洞、多模态、知识库、监控、个人中心等全部页面 JSX 文本替换为 `t()` |
| 游戏翻译 | 蛇王争霸 / 城堡围攻 / 乒乓球动态文本同步翻译（击杀、复活、道具、领主排行榜、兵种、城堡名、玩家状态等），模块级函数用 `T()` 代理 `_t` 规避闭包旧值 |
| 管理后台 | AdminModels 模型管理页（提供商 / 模型配置 / 密码解锁）全量翻译 |
| 私聊与语音 | PersonalChat（限流 / 熔断 / 连接状态）、useVoiceInput / useSpeechSynthesis 提示、OnlinePresenceTracker 在线状态 |
| UI 收纳 | 电脑端首页 hero 顶部语言切换横条移除（统一收纳至 NavBar）；手机端中英文切换按钮移至公告喇叭左侧 |
| 公告栏 | 公告更新为 8 月 15 日 6 条变更（全站翻译 / 游戏翻译 / 后台翻译 / 私聊语音 / 首页优化 / 手机端优化），zh/en 同步 |
| 质量校验 | Node 脚本校验 zh/en 键完全对称（无重复无缺失）；ESLint 0 错误 |

### 3. 关键设计

- 插值：`t('castlesiege.killMsg', { killer: 'A', target: 'B' })` → "A 击败了 B"
- **模块级函数翻译模式**（游戏循环 / 模块级 toast 不得用 useState 旧值）：
  ```js
  let _t = (key) => key   // 默认返回 key，避免未渲染时报错
  function T(key, vars) { return _t(key, vars) }
  // 组件内渲染时同步：_t = t
  ```
- 类组件 ErrorBoundary 用 `static contextType = LanguageContext`；品牌名用 `nameKey` 字段动态翻译
- 后端消息判断改字段语义（`last.action === 'home'`），不依赖中文文案匹配

### 4. 验证与部署

- `npm run build` 成功，scp 上传主服务器 `/opt/app/static/chat/`，`index.html` hash 验证一致
- 纯前端改动，无后端代码 / 配置变更，无需重启 Java 服务

---

## 〇〇〇〇、chat-llm `@MapperScan` bean 冲突修复（2026-08-15）

### 1. 现象

chat-llm 双实例（9095/9096）prod 启动失败，日志报：

```
Error creating bean with name 'conversationMemoryService':
Unsatisfied dependency expressed through method 'setMemoryStore' parameter 0:
No qualifying bean of type 'com.example.chat.llm.rag.legacy.MemoryKVStore' available:
expected single matching bean but found 2: redisMemoryKVStore, memoryKVStore
  - redisMemoryKVStore: defined in RedisMemoryKVStore.class (@Component)
  - memoryKVStore: defined in MemoryKVStore.class
```

### 2. 根因

`LlmApplication.MapperScanConfig` 的 `@MapperScan` 扫描了 `com.example.chat.llm.rag.legacy` 包，**未限制 `annotationClass`**。MyBatis Mapper 扫描器会将该包下**所有接口**（含无注解的领域接口 `MemoryKVStore` / `VectorStoreLegacy` / `UserFactMemory`）注册为 Mapper 代理 bean，bean 名为接口名首字母小写（如 `memoryKVStore`），与 `@Component` 的 `RedisMemoryKVStore`（`redisMemoryKVStore`）同时成为 `ConversationMemoryService.setMemoryStore()` 注入候选 → "found 2" 冲突。

> 注：本地 standalone profile 测试不报错是因为 `app.mapper-scan.enabled=false` 关闭了 MapperScan；生产 `.env` 中 `NACOS_ENABLED=true` + `RAG_ENABLED=true` 且 `app.mapper-scan.enabled` 默认 `matchIfMissing=true`，MapperScan 启用后触发冲突。

### 3. 修复

`LlmApplication.java` 的 `@MapperScan` 加 `annotationClass = Mapper.class`，仅注册带 `@Mapper` 注解的接口：

```java
@MapperScan(annotationClass = Mapper.class, basePackages = {
        "com.example.chat.repository",
        "com.example.chat.llm.rag.legacy",
        "com.example.chat.llm.llm.routing.db"})
public static class MapperScanConfig {}
```

已核实：`com.example.chat.repository` 下 10 个接口、`RAGRepository`、`LlmRoutingRepository` 均带 `@Mapper` 注解，不受影响；无注解的领域接口不再被误扫。

### 4. 验证

- 重新打包 → 上传（MD5 `4ab4f4e4...`）→ 重启双实例
- 9095/9096 `/actuator/health` 均 `UP`（MySQL/Redis/Neo4j/Nacos 全绿）
- gRPC 9195/9196、HTTP 9095/9096 四端口正常监听
- 日志确认 LLM 提供商路由正常加载

---

## 〇〇〇、chat-llm standalone 纯内存模式（2026-08-14）

### 1. 背景

chat-llm 原 standalone profile 仅作 LLM 网关，RAG / 对话记忆 / 知识图谱 / 模型管理面均依赖外部组件（MySQL/Redis/Neo4j/Milvus），本地演示或单机验证必须搭整套中间件。

### 2. 改造内容

standalone 模式升级为**四大能力全部纯内存实现（零外部依赖）**，通过独立开关切换，生产默认仍走 Milvus/Neo4j 不回归：

| 能力 | 内存实现 | 开关 |
|------|---------|------|
| 模型管理面 | `InMemoryLlmRoutingRepository` | `app.llm.admin.memory=true`（standalone 默认开） |
| RAG 检索 | `InMemoryVectorStoreService`（余弦相似度 TopK）+ `InMemoryRAGRepository` | `app.rag.backend=memory` |
| 对话记忆 | `InMemoryMemoryKVStore`（短/长期）+ `InMemoryUserFactMemoryService` + `FactExtractor`（LLM 抽取事实） | `app.rag.backend=memory` |
| 知识图谱 | `InMemoryKnowledgeGraphService`（`ConcurrentHashMap` 图存储） | `app.knowledge-graph.backend=memory` |

### 3. 配套改动

- 新增抽象接口：`VectorStoreLegacy` / `MemoryKVStore` / `UserFactMemory` / `KnowledgeGraphFacade`，Milvus/Neo4j 版与内存版共用
- 条件装配改布尔开关（`app.llm.admin.enabled` / `app.mapper-scan.enabled`），避免 SpEL 解析含 `&`/`=` 的 JDBC URL 崩溃
- standalone 排除 `Neo4jAutoConfiguration`，`/actuator/health` 正常 UP
- `KnowledgeController` / `LegacyRagController` 在 `securityEnabled=false` 时放行（standalone 无需 Authorization 头）
- `EMBEDDING_API_KEY` 可单独配置向量化 Key，缺省回退 `QWEN_API_KEY`

### 4. 启动与验证

```bash
export DEEPSEEK_API_KEY=sk-xxx   # 需要哪个厂商配哪个
java -jar chat-llm/target/chat-llm-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=standalone --server.port=9095
```

冒烟通过：health UP；provider CRUD；RAG 建库/检索；记忆保存 + 上下文；事实记忆召回 `["用户是Java开发者","用户喜欢微服务架构"]`；图谱 stats `{"entityCount":0,"relationCount":0}`。

> 注意：内存数据**重启即清空**，仅演示/验证用；生产请保持 `milvus`/`neo4j` backend + MySQL。完整 curl 示例见 `chat-llm/STANDALONE.md`。

---

## 〇〇、观点辩论场多模型化 + 树状博弈提速（2026-08-14）

### 1. 辩论模型从「固定三方」升级为「随机组队」

观点辩论场（标准辩论）不再固定豆包/DeepSeek/千问三方，改为**从已配置 chat 模型随机抽取 N 个模型组队**：

- **模型数可自选**：前端「模型数」选择器（3~6 可选，上限 = 已配置 chat 模型个数），请求体带 `model_count`（后端默认 3，钳制 3~6）
- **随机组队**：`DebateProcessor.resolveDebateModels(chatModels, modelCount, excludeLocal)` 从 `llm_model_config` 已启用 chat 模型 `Collections.shuffle` 随机抽取，动态分配参与者 id 0..N-1、整合模型 id=N
- **模型名中文展示**：`ModelRouter.modelDisplayName(provider, model)`（豆包/千问/DeepSeek/Kimi/自研 hermes3 等），`round_start` 事件携带 `model_ids`，前端按 `model_id` 路由流式 token 与整合汇总
- **前端动态上限**：`GET /api/v1/models` 返回全部类型，前端按 `modelType === 'chat'` 过滤后计算可选模型数

### 2. 树状博弈提速（排除本地慢模型）

- **现象**：树状博弈卡顿，单视角等待 10s+（豆包 10.5s / Ollama qwen2.5:3b 12s / deepseek、qwen-plus 0.8s）
- **根因**：树状模式全池随机，2/5 概率抽中本地 Ollama 慢模型，每视角 3 轮串行被最慢者拖累
- **修复**：树状辩论 `resolveDebateModels(excludeLocal=true)` **排除 ollama**，仅从豆包/DeepSeek/千问等快模型随机 3 个；`TreePerspectiveGraphService` 用 `Map<String, BranchInfo>` 动态映射正方/中立/反方分支，删除硬编码 `branchInfo()`
- **效果**：单视角响应从 10s+ 降至 ~1s，仅需重新打包 chat-core 并重启 core 双实例（前端无改动）

### 3. 实现清单

| 文件 | 改动 |
|------|------|
| `chat-core/ModelRouter.java` | `toDisplayName` 补 ollama→自研、moonshot→Kimi、openai→GPT、anthropic→Claude；新增 `modelDisplayName(provider, model)` |
| `chat-core/DebateProcessor.java` | `parseModelCount`（默认 3，限 3~6）+ `resolveDebateModels`（随机抽取、树状排除 ollama）+ 动态 id + `round_start` 带 `model_ids` + 整合 token 带 `model_id` |
| `chat-core/DebateTreeProcessor.java` | 动态角色（debaters.get(0)=正方 / get(2)=反方 / get(1)=中立）+ `displayName(cfg)` |
| `chat-core/TreePerspectiveGraphService.java` | 删除硬编码 `branchInfo`，改 `Map<String, BranchInfo>` 动态分支映射 |
| `chat-web/DebateController.java` | 透传 `model_count`（默认 3，限 3~6） |
| `frontend/Debate.jsx` | `modelCount` state + 「模型数」选择器（样式与场次选择器一致）+ `availableModels` 过滤 chat + 动态模型标签/预览/占位符 |
| `frontend/DebateTreeView.jsx` | `roleModelNames` 动态角色名 + 图例动态化 |

### 4. 验证

- 生产部署：core 9090/9092 双实例重启健康（HTTP 200），前端重新 build 上传 Nginx
- 树状博弈实测不再抽中 Ollama，单视角响应 ~1s

---

## 〇、B 档架构重构（2026-08-13）

### 1. RAG 双体系二选一 → 保留 legacy，退役新版

- 新版 RAG（多数据源 `RagService` / `RAGController` / `RagGrpcService` / `rag.enabled` 开关）无任何调用方，代码全部下线
- 删除 18 个 Java/proto 文件 + `rag:` 配置段（本地 yml + Nacos `chat-llm-prod.yml`）
- 保留 legacy 体系（`app.rag.enabled` 控制，`rag_knowledge_bases`/`rag_documents` + Milvus + `KnowledgeController`）

### 2. LLM 配置三源归一 → 新表为唯一运行时源

- 旧表 `model_configs` 退役（仅存档）；运行时统一读取 `llm_provider_config` / `llm_provider_props` / `llm_model_config` / `llm_model_props`
- `ModelConfigRepository` 全部方法改查新表，`ModelConfig` 视图语义不变（39 个消费方零改动）
- 迁移脚本 `docs/sql/migrate_model_configs_to_llm.sql`：实时读取 `model_configs` 数据（不硬编码密钥），保持模型 id 一致（Redis 个人绑定不失效）
- `scripts/insert_model_configs_from_env.sh` 改写入新表
- 注意：迁移前旧 Redis 中 `personal_model:{userId}` 绑定因模型 id 一致而自动保留

### 3. 废弃表清理（2026-08-13）

新版 RAG 体系下线 + LLM 三源归一后，以下 5 张表零代码读写（grep 全仓无 Java 引用），已从生产 RDS 删除：

| 表 | 原因 |
|----|------|
| `model_configs` | 三源归一退役，运行时已切 `llm_*` 新表（9 行历史数据） |
| `llm_data_source` / `llm_data_source_props` | 新版 RAG 数据源表，代码已删（1 行残留） |
| `llm_vector_store_config` / `llm_vector_store_props` | 新版 RAG 向量库配置表，代码已删（1+5 行残留） |

- 删除前备份：`/opt/app/backup/deprecated_tables_20260813.sql`（mysqldump 全量）
- `llm_routing_schema.sql` 已同步移除废弃表 DDL 与示例数据

### 4. Multi-Agent 可靠性加固（Reconciler ZSet 索引 + Worker 死信重试）

- **Reconciler 扫描 O(N) → O(logN)**：新增 ZSet 索引 `agent:reconciler:plans`（score=下次检查时间戳），结果到齐置 0 立即纳入，收敛成功 `ZREM` 移除；`ZRANGEBYSCORE 0 now LIMIT 500` 只取到期候选，存量 plan 兜底 keys() 扫描随 30min TTL 自然淘汰
- **Worker 失败指数退避重试**：新增死信链路 `agent.subtask.dlx` + `agent.subtask.dlq`（TTL 兜底 60s）；失败按 `x-death` 累计次数 `min(1000×2^n, 60000)`ms 延迟重投 + ack，达 `max-attempts=5` 才回传终态失败，不再 nack 即终态
- **配置**：`app.agent.planner.retry.*`（initial-delay-ms=1000 / max-delay-ms=60000 / max-attempts=5），Nacos `chat-core-prod.yml` 已同步
- **测试**：新增 `SubAgentWorkerTest`（9 例）+ `WorkflowReconcilerTest`（3 例），chat-core 全量 181 用例通过

### 5. 豆包 404 事故与修复（2026-08-13 晚，观点辩论豆包失联）

- **现象**：观点辩论/自动问答中正方（豆包）不出内容，日志 `doubao 返回 404`，请求被静默降级到 DeepSeek 兜底
- **根因（双错误叠加）**：
  1. **模型名被迁移数据覆盖为错误值**：`llm_model_config.id=3` 的 `model_name` 为 `doubao-seed-character-260628`（迁移期间从旧表带入的错误名），方舟 API 上不存在 → 404
  2. **迁移脚本缺失 `path` 属性**：`llm_provider_props` 只写入了 `api_key`，未写入 `path`；`LlmProviderAdminService.DEFAULT_PATH="/v1/chat/completions"` 与豆包 base_url `https://ark.cn-beijing.volces.com/api/v3` 拼接成 `/api/v3/v1/chat/completions` → 404（joinUrl 重叠合并仅对以 `/v1` 结尾的 base 生效；静态 yml 中豆包明确配置了 `path: /chat/completions`，三源归一后 DB 路由丢掉了该信息）
- **修复**：
  1. `UPDATE llm_model_config SET model_name='doubao-seed-2-0-pro-260215' WHERE id=3`（与静态 yml 一致）
  2. `INSERT llm_provider_props(provider_config_id=24, prop_key='path', prop_value='/chat/completions')` + POST `/api/v1/llm/admin/providers/reload` 热重载（无需重启，9095/9096 双实例都执行）
  3. 迁移脚本 `docs/sql/migrate_model_configs_to_llm.sql` 新增 §3.5：按厂商显式写入 `path`（doubao/deepseek/zhipu → `/chat/completions`，qwen → `/v1/chat/completions`），防再次迁移复现
- **验证**：`POST /api/v1/chain/invoke {provider:doubao, model:doubao-seed-2-0-pro-260215}` → 返回豆包真实内容、`fallback=false`；curl 直连方舟 `/api/v3/chat/completions` 200、错误 URL `/api/v3/v1/chat/completions` 404（确认根因）
- **无代码改动**（纯 DB 数据修复 + 迁移脚本补丁），chat-core 的 `ModelConfigRepository` 每次请求实时查库，无需重启

> **注（2026-08-16 更新）**：chat-core 此后引入 `CachedModelConfigRepository`（`@Primary`，内存 Map + 每 60 秒定时刷新），`ModelConfigRepository` 已改为「缓存读 + 定时刷新」而非每次实时查库；chat-llm 侧也新增 `LlmProviderAdminService.scheduledRefresh()` 每 60 秒从 DB 重载 provider 路由，改 key 无需重启。

---

## 一、树状辩论模式

全新的多维分析辩论：将复杂问题拆解为多个视角，多模型并行辩论后汇总综合结论。

```
用户提问 → LLM 语义拆解 → 2~3 个分析视角
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
            视角A           视角B           视角C
         (N 个快模型随机组队 3轮辩论, LangGraph 编排)
              │               │               │
              ▼               ▼               ▼
           视角结论        视角结论        视角结论
              └───────────────┼───────────────┘
                              ▼
                        LLM 综合汇总
```

### 特性

- **智能拆解**：LLM 按问题语义自动生成 2~3 个分析角度，异常时回退默认视角
- **多方辩论**：每视角 N 个快模型（随机组队，名称中文展示）3 轮交锋
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
- **CORS 白名单**：`localhost:*` / 生产域名 / `your-domain.com`，加 CSP 头防 XSS
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
- **chat-llm bean 注册**：`LlmApplication` 通过 `@MapperScan(annotationClass = Mapper.class)` 扫描 chat-common `repository` 包与 `rag.legacy` 包（仅注册带 `@Mapper` 注解的接口，避免无注解领域接口如 `MemoryKVStore` 被误注册为 Mapper bean 与 `@Component` 实现类冲突）；`@Import` 显式注册 `LlmConfigProperties` / `DirectLLMClient` / `BaseUrlResolver` / `JwtUtil`（均位于 `com.example.chat.*` 默认扫描路径外）
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

> **演进（2026-08-16）**：DB 已确立为 provider key 的**唯一真相源**，chat-llm 新增 `@Scheduled(fixedRate=60000) scheduledRefresh()` 每 60 秒从 DB 重载 provider 路由（复用 `loadDbProviders()` 仅覆盖同名项、不清空），改 key 无需重启；`.env` 中的 `*_API_KEY` 仅作 standalone/DB 故障兜底。详见「LLM key 定时刷新 + DB 唯一真相源收敛」变更记录。

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

---

## 十五、编排范式术语规范化（文档）

对齐业界范式命名，无代码改动：

- **树状辩论 = Plan-and-Execute 混合模式**：`decompose()`（Plan：LLM 拆解视角）→ 视角并行执行（Execute）→ `aggregate()`（汇总），详见 `架构设计说明.md` §6.3 / `架构全盘说明.md` 5.3 / `部署运维手册.md` §8 / `项目概述.md`
- **工具调度 = ReAct（Reasoning and Acting）循环**：`ToolDispatcher` 带 `tools` 调 LLM → 提取 `tool_calls` → 执行工具 → 结果回填 → 再决策（`maxToolCalls=3`），仅个人对话场景，详见 `方案PPT内容.md` §7.3 / §8.3
- **标准辩论**保持 LangGraph4j `StateGraph` 图式循环描述（非 plan-and-execute）
- 涉及文档：`架构设计说明.md`、`架构全盘说明.md`、`部署运维手册.md`、`项目概述.md`、`方案PPT内容.md`、`系统架构说明.md`

---

## 十六、辩论引入 Reflection 反思循环（功能）

标准辩论与树状辩论加入 **Reflection（批判性反思）** 节点，解决"对抗但不迭代"的质量短板——每轮辩论后 N 方不再直接堆叠观点，而是审视对方反驳后修正立场。

### 特性

- **reflect 反思节点**：每轮 `debate` 之后新增 N 方并行反思分支，各自读取「本轮自己观点 + 对方观点」，输出：1) 被反驳得有道理的点 2) 是否修正 3) 修正后的立场（≤100 字）
- **裁决式汇总**：`summary` 基于反思后的最终立场（`{{state.proReflections[-1]}}` 等）输出【正方强调 / 反方强调 / 中立评价 / 关键分歧 / 共识结论】，替代机械归纳
- **事件可见化（2026-08-15 追加）**：reflect 节点事件现广播 `reflecting` WS 事件，前端展示「模型正在批判性反思上一轮观点...」提示，消除「第二轮启动慢」的静默期；`round_start → round_response → round_end` 主流程不变（详见顶部最新 CHANGELOG 条目）
- **成本**：每轮辩论 LLM 调用从 3 次增至 6 次（3 辩论 + 3 反思），`maxSteps` 相应调整为 `rounds*4+2`

### 实现

| 文件 | 改动 |
|------|------|
| `DebateGraphService.java` | 图增加 `reflect` 节点（N 方分支 sink 到 `proReflections/conReflections/neutralReflections`）；`summary` 提示词改为基于反思后立场裁决；边 `debate → reflect → shouldContinue` |
| `TreePerspectiveGraphService.java` | 同上（sink 到 `model1Reflections/model2Reflections/model3Reflections`，50 字限制） |

### 验证

- chat-core 全量 154 测试通过
- 生产部署：core 9090/9092 重启健康（HTTP 200）
- 生产 jar 字节码校验：`DebateGraphService`/`TreePerspectiveGraphService` 均含 reflect 节点逻辑

---

## 十七、情绪树洞 Memory 记忆增强（用户画像，功能）

情绪树洞开启 **记忆增强**：不再只记忆对话原文，而是由 LLM 从每次倾诉中提炼出用户画像——**情景 + 情绪 + 个人偏好**，逐步累积，后续回答根据偏好自动调整风格。

### 特性

- **三层记忆架构**：
  1. 短期记忆（Redis）：最近 5 轮对话原文
  2. 长期记忆（Milvus）：全部历史向量化，按语义相关性检索（top-3，阈值 0.5）
  3. **用户画像**（Redis `user_profile:{scene}:{userId}`，TTL 30 天）：LLM 提炼的结构化画像
- **画像提炼**：每次对话保存后异步调用 LLM（默认 qwen/qwen-plus，`app.rag.memory.profile-*` 可配），从"用户倾诉 + 树洞回应"中提炼 `scene`（当前情景）、`emotions`（情绪 ≤3）、`preferences`（偏好风格 ≤3）、`context`（背景）
- **增量合并**：情景/情绪取最新；偏好合并去重（上限 10 条）持续累积；背景追加最近 5 条——画像随对话越聊越懂用户
- **回答贴合偏好**：`buildMemoryContext` 将【用户画像】段落注入记忆上下文，并附引导"请根据用户画像中的情绪与偏好调整你的回应风格"，经 `TreeHoleHistoryBuilder` 拼入树洞 system prompt
- **异步无侵入**：画像提炼在 chat-llm 线程池异步执行，失败仅告警不影响保存主流程；LLM 输出自动剥离 ```json 代码块包裹

### 实现

| 文件 | 改动 |
|------|------|
| `chat-llm/ConversationMemoryService.java` | 新增 `updateUserProfile` / `extractUserProfile` / `mergeProfile` / `getUserProfileContext` 等；`buildMemoryContext` 前置注入【用户画像】段落 |
| `chat-llm/LegacyRagController.java` | `/internal/rag/memory/save` 保存对话后异步 submit 触发画像提炼 |
| `chat-llm/application.yml` | 新增 `app.rag.memory.profile-ttl-days` / `profile-provider` / `profile-model` 配置 |

### 验证

- 生产验证：保存测试对话后 2 秒内画像生成；第二次对话后偏好增量合并（保留旧偏好 + 新增新偏好）；`/internal/rag/memory/context` 返回含【用户画像】段落
- 生产部署：chat-llm 双实例 9095/9096 重启健康（HTTP 200）
- 测试数据已清理（Redis `user_profile:treehole:999999` / `memory:treehole:999999`）

---

## 十八、对话自动 RAG 增强（功能）

个人对话空间与 AI 伙伴群聊接入 **自动 RAG 检索增强**：当用户询问知识/事实类信息（如天气、资料性查询）时，自动从默认知识库检索相关内容注入回答，让 AI 依据知识库作答。

### 特性

- **意图驱动检索**：三层漏斗识别出 `KNOWLEDGE_QA`（知识问答）/ `TASK_EXECUTION`（任务执行，含天气）意图时自动触发知识库检索；闲聊/情感类不触发，零成本
- **命中增强、未命中降级**：检索到相关资料（相似度 ≥ 0.3）才把【参考资料】注入 system prompt 引导作答；知识库为空、未命中或检索失败时完全回退普通回答，不影响对话
- **个人 + 群聊全覆盖**：个人对话（流式）与群聊（并发竞速）均接入；群聊检索一次，注入所有并发模型
- **与工具互补**：天气等实时信息仍优先走 `weather` 工具（wttr.in）获取实时数据；知识库有资料时 RAG 提供本地/定制资料依据

### 配置

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `app.rag.chat.enabled`（`CHAT_RAG_ENABLED`） | true | 对话自动 RAG 总开关 |
| `app.rag.chat.kb-id`（`CHAT_RAG_KB_ID`） | 0 | 默认检索的知识库 ID（`rag_knowledge_bases.id`），≤0 表示未配置不检索 |
| `app.rag.chat.top-k` | 3 | 检索返回条数 |
| `app.rag.chat.score-threshold` | 0.3 | 相似度阈值 |
| `app.rag.chat.max-chars` | 2000 | 参考资料字符上限 |

### 实现

| 文件 | 改动 |
|------|------|
| `chat-core/ChatProcessor.java` | 新增 `shouldAutoRag` / `buildChatRagContext` / `buildChatRagSystemPrompt`；个人流式（`doPersonalStream`）与群聊并发（`doGroupConcurrent`）在 LLM 调用前注入 RAG 参考资料 |
| `chat-core/application.yml` | 新增 `app.rag.chat.*` 配置段 |

### 验证

- chat-core 编译通过，生产部署 core 9090/9092 重启健康（HTTP 200）
- 生产现状：知识库尚无内容（`rag_knowledge_bases` 表 0 条 / 新版数据源 retrieve 0 命中）→ 功能安全待命；上传知识库后设置 `CHAT_RAG_KB_ID=<ID>` 即自动生效（服务器 `.env` 已预置配置项）

### 知识库可查性判断框架（哪些问题值得从 Milvus 查）

是否检索知识库由**三层判定**决定（`ChatProcessor.shouldAutoRag`）：

```
第1层 开关/配置   → RAG 启用 && 配置了默认知识库（CHAT_RAG_KB_ID>0）
第2层 意图判定    → KNOWLEDGE_QA（知识问答）或 TASK_EXECUTION（任务执行）
第3层 可查性判定  → 排除实时数据类 + 个人数据类（知识库无此类内容）
检索后相似度判定  → score < 0.3 的片段丢弃，全部低于阈值则不注入（降级普通回答）
```

| 问题类型 | 例子 | 查知识库？ | 处理 |
|----------|------|:---:|------|
| 概念/定义/百科 | "什么是蒙特卡洛模拟"、"退货政策是什么" | ✅ 查 | RAG 依据知识库作答 |
| 产品/资料性查询 | "你们支持哪些支付方式" | ✅ 查 | RAG 依据知识库作答 |
| 专业知识 | "解释一下贝叶斯定理" | ✅ 查 | RAG + LLM 补充 |
| 教程/操作 | "怎么配置接口鉴权" | ✅ 查 | RAG 检索文档 |
| 实时天气 | "今天北京天气怎么样" | ❌ 不查 | weather 工具（wttr.in）或模型回答 |
| 实时时间/日期 | "现在几点"、"今天星期几" | ❌ 不查 | 模型/工具 |
| 实时新闻/行情 | "今天有什么热点新闻"、"A股现在什么行情" | ❌ 不查 | 模型/工具 |
| 体育比分 | "昨晚比赛结果" | ❌ 不查 | 模型/工具 |
| 个人数据 | "我的订单/余额/消息" | ❌ 不查 | 系统无此数据 |
| 闲聊/情感/创作/翻译 | "你好"、"我很焦虑" | ❌ 不查 | 意图本身不在第2层 |
| 代码/推理/计算 | "写个排序算法"、"1+1等于几" | ❌ 不查 | 意图本身不在第2层 |

**实现**：`ChatProcessor` 新增 `isRealTimeOrPersonalQuery`（预编译正则：天气/时间/新闻/行情/比分/个人数据）；`intent-rules.json` 补充天气关键词规则（L1 直接归入 `TASK_EXECUTION`）。生产 core 9090/9092 已部署健康。

---

## 十九、Multi-Agent 并行工作流与可靠性加固（功能，2026-08-13）

超长/跨域请求由单 Agent 串行处理升级为 **TaskPlanner → RabbitMQ → 双实例 Worker 并行 → 收敛** 的完整编排链路：一次 LLM 长回答拆解为最多 9 个子任务并行执行，主 Agent 收敛压缩输出（≤ 1000 字），复杂任务耗时大幅缩短。

### 特性

- **智能拆解**：`TaskPlanner.shouldDecompose` 按长度（≥ min-length）与 LLM 判定是否拆解；简单问题走普通流式，零开销
- **Redis Lua 原子限流（全局并发控制）**：`agent:workflow:active` 用 Lua INCR/DECR 脚本做双实例共享计数，并行工作流上限 `max-concurrent=8`，超限自动降级普通流程（不排队、不拒绝）；修复早期 Semaphore 跨实例 acquire/release 错配（9092 曾释放 9090 许可致计数异常）
- **manual ack + prefetch=1（公平分发 + 零丢失）**：Worker 忙时不 ack 不拉新消息、空闲即拉，按真实能力均衡分发；子任务执行中实例挂掉由 RabbitMQ requeue 自动重投，测试验证零丢失
- **Redis 结果聚合 + 收敛锁**：结果按 taskId 幂等覆盖写 hash，`received` 原子 INCR，SETNX 锁保证双实例只收敛一次；失败结果回传 + `nack(requeue=false)` 防无限重试重复计数
- **WorkflowReconciler 收敛对账（方案 A 可靠性兜底）**：每 30s 扫描 Redis 中"结果已齐但收敛未完成"的 plan 并重新触发收敛，兜底"收敛中途崩溃重启后卡死"盲区；以 `converged` 标记 + DB 状态双重去重，锁 TTL 5min
- **输出压缩**：收敛用轻量模型（qwen-turbo）汇总 + `converge-max-chars` 硬截断，最终回答 ≤ 1000 字

### 配置（Nacos `chat-core-prod.yml`）

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `app.agent.planner.enabled` | true | 并行工作流总开关 |
| `app.agent.planner.min-length` | 120 | 触发拆解的最小问题长度 |
| `app.agent.planner.max-tasks` | 9 | 最大子任务数 |
| `app.agent.planner.task-timeout-ms` | 120000 | 子任务软超时 |
| `app.agent.planner.max-concurrent` | 8 | 全局并行工作流上限（Redis 原子限流） |
| `app.agent.planner.converge-max-chars` | 1000 | 收敛输出最大字数 |
| `app.agent.planner.converge-model` | qwen-turbo | 收敛专用轻量模型 |
| `app.agent.planner.reconcile-interval-ms` | 30000 | Reconciler 对账扫描周期 |
| `spring.rabbitmq.listener.simple` | concurrency=10 / max-concurrency=20 / prefetch=1 | Worker 并发与预取 |

### 实现

| 文件 | 改动 |
|------|------|
| `chat-core/agent/planner/TaskPlanner.java` | 拆解判定 + LLM 计划生成 |
| `chat-core/agent/planner/AgentWorkflowOrchestrator.java` | 编排：Redis Lua 限流 / 分发 / 收敛 / 许可释放；收敛成功写 `converged` 标记 |
| `chat-core/agent/workflow/SubTaskRabbitConfig.java` | durable 交换机/队列定义 |
| `chat-core/agent/workflow/SubTaskProducer.java` | 任务/结果投递 |
| `chat-core/agent/workflow/SubAgentWorker.java` | Worker manual ack；失败回传 + nack(requeue=false) |
| `chat-core/agent/workflow/SubTaskResultCollector.java` | 结果 hash 幂等聚合 + received INCR + 收敛锁触发；失败 requeue=true |
| `chat-core/agent/workflow/WorkflowReconciler.java` | 新增：30s 周期对账，重新触发卡住 plan 的收敛 |
| `chat-common/config/RabbitConfig.java` | `concurrentConsumers` 改为 `@Value` 注入（默认 10/20/5） |
| `chat-common/agent/protocol/SubAgent*.java` | 计划/任务/结果协议 |

### 验证（生产双实例）

- 单请求全链路：queued → 分发 9 子任务 → 收敛 answerLen 540~790 ≤ 1000 → DB done
- **20 并发压测：精确 8 并行 + 12 降级，合计 20 全有明确去向；8/8 全部收敛完成；`agent:workflow:active` 归零无泄漏**
- **manual ack 零丢失：8 个 plan 子任务执行完成数 == 分发数**
- **Reconciler 端到端**：伪造"结果已齐但收敛未执行"的卡住 plan → 30s 内自动重新收敛 → DB processing→done；删除锁模拟锁过期后不再重复触发（converged 标记去重）
- 测试套件 `scripts/test-multiagent-suite.sh` 全量 PASS=12 / FAIL=0（T01-T06）

---

## 九、2026-08-13 代码质量与架构优化（P0/P1/P2 全部落地）

> 面向「可维护性」的系统性重构：消除跨模块重复代码、统一响应/鉴权/限流出口、拆分上帝类。
> 全部改动已编译验证（`mvn install` 全绿）+ 单元测试通过，并完成生产部署。

### 1. 前端 axios 统一封装（P0-1）

- 新增 `frontend/src/config/http.js` 的 `apiClient`（请求拦截器自动附加 JWT `auth_token` + 401 统一登出）
- **15 个页面 52 处裸 `import axios` 调用全部迁移**，删除各页面私有 `getAuthHeaders()` / `authHeader` 等重复代码
- 修复 token 键名 bug：`localStorage.getItem('token')` → `'auth_token'`（与 App.jsx 写入键一致）
- `SqlExecutor` 保留独立 `sql_token` 会话，`X-Admin-Token` header 保留

### 2. 鉴权统一（P1-4）

- `chat-common/security/AuthUtils` 新增 `extractUserIdFromContext()` / `extractUsernameFromContext()`（读 SecurityContextHolder，由 JwtAuthenticationFilter 填充）
- `MediaGenController`、`CastleSiegeLordController` 删除各自私有实现与 `JwtUtil` 依赖

### 3. 限流统一（P1-5）

- 新建 `chat-common/security/RateLimitChecker`：统一 Redis 固定窗口计数（increment + 首设 TTL + fail-open）
- `RateLimitService`（用户级 20/min + 200/hour）与 `IpRateLimitInterceptor`（IP 全局 600/min + 敏感接口 10/min）全部接入，删除重复的 `checkRate()`

### 4. LLM 工具调用下沉 chat-common（P1-3）

- 新建 `chat-common/util/LlmToolInvoker`（OpenAI 兼容 function calling：POST /chat/completions + tool_calls 提取 + 参数解析 + 工具执行）
- 新建 `chat-common/util/LlmToolExecutor`（函数式回调接口，解耦 `ToolRegistry`）
- `ToolDispatcher` 与 `SubAgentWorker` 各删除约 6 个重复私有方法，工具执行经回调注入，协议层唯一

### 5. 统一响应体与全局异常（P1-6）

- 新建 `chat-common/common/ErrorCode` 枚举（400/401/403/404/429/500）
- `ApiResponse` 增强：新增 `success` / `fail` / `ErrorCode` 重载（旧方法向后兼容）
- `GlobalExceptionHandler` 输出统一为 `{"ok":false,"code":<HTTP状态码>,"error":"..."}`（原为 timestamp/status/error/message 三处不一致格式）
- **修复 chat-llm 异常出口缺失**：`LlmApplication` 默认只扫 `com.example.chat.llm`，全局异常处理从不生效 → `@Import(GlobalExceptionHandler.class)` 补齐

### 6. ChatProcessor 拆解（P2-3，1019 → ~830 行）

- 抽出 `ChatRagEnhancer`：RAG 三层判定 / 知识库检索 / system prompt 构建 + 实时/个人数据正则 + 5 个 `app.rag.chat.*` 配置项
- 抽出 `ChatCacheManager`：缓存 key 构建（SHA-256）/ 命中广播回填 / 24h TTL 写入
- 个人流式 + 群聊竞速两条链路统一复用，行为零变化

### 7. chat-media JVM 内存减配

- `restart-media.sh` 增强：`-Xmx160m -Xss256k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:G1HeapRegionSize=1m` + `--server.tomcat.threads.max=50`
- 生效结果：RSS 475MB → **415MB**，VSZ 4070MB → 2911MB（media 因 `transferToOss` 需缓存整个视频 byte[]，保留 160m 而非 games 的 128m）

### 验证与部署

- 单测通过：`RateLimitServiceTest` / `IpRateLimitInterceptorTest` / `ApiResponseTest` / `ChatProcessorTest` / `ToolDispatcherTest`
- 生产验证：login 接口返回新版错误体 `{"error":"参数错误...","code":400,"ok":false}`；4 个 jar 内嵌 chat-common 均确认新版
- **部署教训**：`mvn ... | tail` 管道会掩盖 Maven 真实退出码，全量构建后需按 jar mtime 校验每个模块是否真正重新打包（本次 chat-web 曾漏打包，上传旧 jar 导致异常格式未生效）

---

## 二十、测试质量专项：chat-web 反射/弱测试清零（W8，2026-08-13）

> 测试质量专项 W5/W6/W7/W8 收官：chat-web 残留的"类存在验证 / 反射（getMethod / Class.forName / assertNotNull(X.class)）/ 仅构造"弱测试全部升级为真实行为断言（AAA 模式 + Mockito 5.14.2）。

### 变更内容

| 项 | 内容 |
|----|------|
| 重写测试类 | **11 个** → **66 例**真实行为断言：RestTemplateConfig（超时字段 + MDC TraceId 透传）5 / CoreClient（init 去重·故障转移·base64 payload·stop 广播 9090+9092）6 / AttachmentController（落库字段）2 / AuthController（登录注册全流程）6 / DebateController（敏感词 400·rounds 钳制 1-10·mode 透传）4 / IpAdminController（鉴权·黑名单解析·拉黑解封）6 / MessageController（429+retry_after·敏感词·新用户自动创建·ai_answer 透传·regenerate/stop 守卫·在线人数聚合）9 / ProfileController（mockStatic AuthUtils）5 / MonitorController（密码登录·online-history 聚合·llm-stats 解析·traces 降级·快照落库）9 / KnowledgeBaseController（Authorization 透传·空文件 400·多文件上传·deleteDocument 3 参签名）7 / TreeHoleController（mockStatic 守卫）7 |
| 删除 | 废弃空类 `ModelConfigController`（0 字节全注释）+ 仅 InjectMocks 的 `ModelConfigControllerTest`（本次收尾补删残留空文件） |
| 结果 | chat-web 全量 **87 例全绿**；全仓 **700 例全绿**（chat-common 272 / chat-core 197 / chat-web 87 / chat-llm 74 / chat-games 44 / chat-media 26） |

### 关键坑记录（W8 新增）

1. **Spring 6.0.x 无超时 getter**：`SimpleClientHttpRequestFactory` 无 `getConnectTimeout()/getReadTimeout()` → 用 `ReflectionTestUtils.getField(factory, "connectTimeout")` 读私有 int 字段；且设置拦截器后 requestFactory 被包装为 `InterceptingClientHttpRequestFactory`，需再解包一层 `getField(requestFactory, "requestFactory")`
2. **`Map.of` 不允许 null 值**：mock `insert` 未回填主键 → 响应体 `Map.of` 抛 NPE → 用 `thenAnswer` 回填 id/token（`generateToken` 必须 stub）
3. **Mockito strict stubbing**：`lenient()` 用于 setUp 共享 stub；verify/when 混用原始值与匹配器触发 `InvalidUseOfMatchers` → 全参 `eq()`/`any()`
4. **mockStatic 对 null 实参匹配边界**：`AuthUtils.extractUserId(null)` 期望 401 实际 404 → 401 用例改用非空无效 token；拉黑用例验证方向用 `never()`（此前误验证"被调用"）
5. **`KnowledgeBaseController.deleteDocument` 实为 3 参签名** `(kbId, docId, request)`；`ModelConfigControllerTest` 残留 0 字节空文件需物理删除
6. **lint 提示清理**：`ClientHttpResponse` 未用 try-with-resources（改为 try-with-resources + mock 实例）、响应体 `Map.get` 可能 NPE（补 `assertNotNull` + 局部变量提取）

### 验证

- `mvn test` 全模块 **BUILD SUCCESS**（700 例全绿）；`mvn -pl chat-media -am test-compile` 编译通过（IDE 索引误报 `Cannot resolve method 'any'` 经编译证伪）
- 全库正则扫描确认无 `Class.forName` / `getMethod` 反射残留
- 文档同步：`测试规范.md` / `架构评估报告.md` / 本 CHANGELOG / 根 `README.md`（87 例 + 700 例全绿）

## 二十一、可观测性补强：业务级指标 + 4 条业务告警（2026-08-13）

> 补齐告警覆盖的业务维度：此前 Prometheus 告警以系统层为主（服务宕机/JVM/CPU/负载/磁盘/内存/延迟/错误率），业务运行质量无指标可查、无告警可依。本次为 chat-core 新增业务级指标收集器，上线 4 条业务告警。

### 变更内容

| 项 | 内容 |
|----|------|
| 新增类 | `chat-core/.../observability/CoreBusinessMetrics.java`（`MeterBinder` 注入 `MeterRegistry`；经 chat-common 传递依赖 micrometer-registry-prometheus 1.11.6，**无需新增 Maven 依赖**，模式与 chat-llm `LlmMetrics` 一致） |
| 意图漏斗指标 | `core.intent.funnel.hits`（tag: layer=L1/L2/L3/FALLBACK）+ `core.intent.funnel.latency`（Timer）→ `IntentFunnelEngine.recognize` 四个返回分支埋点，L1+L2 综合命中率可计算 |
| Multi-Agent 工作流指标 | `core.agent.workflow.started`（tag: status=parallel/degraded）+ `core.agent.workflow.converged`（tag: status=success/failed）→ `AgentWorkflowOrchestrator.tryParallelWorkflow`（finally 统一记录 parallel/degraded，覆盖并发过载降级路径）与 `converge`（success/failed）埋点 |
| 业务告警 | `docs/prometheus-alert-rules.yml` 新增 `chat-system-business` 组 4 条：IntentFunnelHitRateLow（漏斗 L1+L2 命中率 <85%）/ AgentWorkflowDegradeHigh（降级率 >50%）/ AgentWorkflowConvergeFail（收敛失败） / LLMTokenSurge（LLM 侧数据源为 chat-llm 既有 `llm.invoke.tokens`） |
| 测试 | `CoreBusinessMetricsTest` 6 例（SimpleMeterRegistry 真实断言：分层计数/耗时/降级/收敛/null 回退 unknown/未 bindTo 安全空操作）→ chat-core 197 → **203 例全绿** |
| 评分影响 | 架构评估报告可观测性 **4/5 → 5/5**，综合 **96 → 97**、纯软件 **97 → 98**（README / docs README / 部署运维手册同步） |

### 指标清单（Prometheus 名称）

```
core_intent_funnel_hits_total{layer=...}          意图漏斗各层命中/回退计数
core_intent_funnel_latency_seconds{layer=...}     漏斗识别耗时
core_agent_workflow_started_total{status=...}     工作流启动（parallel=成功接管 / degraded=并发过载/计划失败降级）
core_agent_workflow_converged_total{status=...}   工作流收敛（success / failed）
llm_invoke_tokens_total{type=...}                 LLM token 消耗（chat-llm 既有，LLMTokenSurge 数据源）
```

### 关键设计

1. **无新依赖**：chat-core 经 chat-common 传递获得 micrometer（1.11.6 与 Boot 3.1.6 匹配，勿升级 1.13.0 否则 `/actuator/prometheus` 404）
2. **安全降级**：`@Autowired(required = false)` + registry 判空，单测/无 Prometheus 环境空操作不抛异常
3. **双实例一致**：core 双实例（9090/9092）各自上报，Prometheus 抓取后按 instance 聚合，漏斗命中率/降级率按实例计算
4. **指标名即文档**：`CoreBusinessMetrics` 类注释内嵌全部度量维度与对应告警，避免指标漂移

### 部署

1. `mvn clean install -DskipTests`（chat-core 含新指标类）
2. 上传 jar 并重启 core 双实例（`restart-core.sh all`）
3. 上传 `docs/prometheus-alert-rules.yml` → `/opt/app/prometheus/alerts.yml`，`curl -X POST http://127.0.0.1:9094/-/reload`
4. 验证：`curl -s http://127.0.0.1:9090/actuator/prometheus | grep -E 'core_(intent|agent)'`

### 验证

- `mvn -pl chat-core test`：**203 例全绿**（含新增 `CoreBusinessMetricsTest` 6 例，197 + 6）
- `mvn -pl chat-core -am test-compile` 编译通过（IDE 对 `recordMetrics` 的 "Cannot resolve" 报错为索引陈旧误报，与 W8 的 `Cannot resolve method 'any'` 同类，经 Maven 编译证伪）
- 文档同步：`架构评估报告.md`（可观测性 4→5、综合 96→97、纯软件 97→98）/ 根 `README.md` / `docs/README.md` / `部署运维手册.md`（告警 8 → 12 条：系统 8 + 业务 4）

## 二十二、业务指标采集切面化：手写埋点 → AOP 横切（2026-08-13）

> 二十一节的业务指标采集为手写埋点（业务类内直接调用 `CoreBusinessMetrics`）。指标采集本质是横切关注点，手写埋点让 `IntentFunnelEngine` / `AgentWorkflowOrchestrator` 掺杂埋点逻辑，业务与监控耦合。本次用 Spring AOP 抽离为切面，业务类零侵入。

### 变更内容

| 项 | 内容 |
|----|------|
| 新增依赖 | `chat-core/pom.xml` 新增 `spring-boot-starter-aop`（Spring AOP，无需版本号） |
| 新增类 | `chat-core/.../observability/CoreBusinessMetricsAspect.java`（`@Aspect @Component`，3 个 `@Around` 切点，复用 `CoreBusinessMetrics` 指标 API） |
| 切点 1 | `IntentFunnelEngine.recognize` → `core.intent.funnel.hits/latency`：从返回值 `FunnelRecognizeResult.source()` 映射 L1/L2/L3/FALLBACK |
| 切点 2 | `AgentWorkflowOrchestrator.tryParallelWorkflow` → `core.agent.workflow.started`：boolean 返回值映射 parallel/degraded |
| 切点 3 | `AgentWorkflowOrchestrator.converge` → `core.agent.workflow.converged`：正常返回记 success / 异常记 failed |
| 回退手写埋点 | `IntentFunnelEngine` 移除 `CoreBusinessMetrics` 字段/import/`recordMetrics()` 及 4 处调用；`AgentWorkflowOrchestrator` 移除 4 处手写埋点 → 业务类零侵入 |
| 异常语义重构 | `converge` 原 catch 吞异常（`@Around` 捕获不到）→ catch 内 `throw new RuntimeException(e)` 抛给切面，切面捕获后记 `failed` 不重抛（对外语义不变，异常类型变为 RuntimeException） |
| 测试 | `CoreBusinessMetricsAspectTest` 9 例（Mockito + ProceedingJoinPoint）→ chat-core **212 例全绿**（197 存量 + 6 metrics + 9 aspect） |

### 关键设计

1. **代理生效前提**：3 个切点均为跨类调用（ChatProcessor / SubTaskResultCollector / WorkflowReconciler 触发），Spring AOP 代理全部生效，无同类自调用绕代理问题
2. **指标名与告警不变**：`CoreBusinessMetrics` API 未改动，Prometheus 指标名与 4 条业务告警规则零影响
3. **采集逻辑收敛**：埋点增减/监控目标变更只改切面一处，业务类不再感知可观测性

### 部署与验证

1. `mvn -pl chat-core -am package -DskipTests` → `scp` jar → `bash /opt/app/restart-core.sh all`
2. 造业务请求后 `curl -s http://127.0.0.1:9090/actuator/prometheus | grep core_intent`
3. 实测：`core_intent_funnel_hits_total{layer="L1"} 1.0`（"帮我查一下今天的天气" 命中 RULES → L1，切面映射正确）

### 验证

- `mvn -pl chat-core test`：**212 例全绿**（197 + 6 + 9）
- 生产实测：切面版 jar 部署 core 双实例（9090/9092）后，首次业务流量触发 `core_intent_funnel_hits_total`（指标懒注册），layer 映射正确；双实例 health 200
- 文档同步：`架构评估报告.md` / 根 `README.md` / `部署运维手册.md` / `ADR-架构决策记录.md`（ADR-022）/ 本 CHANGELOG

---

## LLM key 定时刷新 + DB 唯一真相源收敛（2026-08-16）

### 背景

线上千问模型「观点辩论场出不来」，排查根因是千问 API key 失效（401 `invalid_api_key`）。修复时发现 key 散落两处（DB `llm_provider_props` 表 + `.env` 环境变量）且不一致：`.env` 里 `DEEPSEEK_API_KEY`/`DOUBAO_API_KEY` 也均为失效旧 key，仅 DB 里的是有效值。更关键的是，chat-llm 的 provider 路由**只在启动时加载一次 DB**（`ApplicationReadyEvent`），改 DB 后必须重启才生效，而 chat-core 已有 60 秒定时刷新，两者机制不一致导致「改了 DB 只修好 chat-core、chat-llm 仍用旧 key」。

### 变更内容

| 项 | 内容 |
|----|------|
| chat-llm 定时刷新 | `LlmApplication` 增加 `@EnableScheduling`；`LlmProviderAdminService` 新增 `@Scheduled(fixedRate=60000) scheduledRefresh()`，每 60 秒从 DB 重载 provider 路由 |
| 容错设计 | 定时刷新复用 `loadDbProviders()`（仅覆盖同名项、不清空），**不用** `reload()`——后者会 `registry.reset()` 清空全部路由，清空后 DB 瞬时故障会留下「只剩 YAML 兜底」的脆弱窗口 |
| key 真相源收敛 | 确立 DB `llm_provider_props` 为 provider key 唯一真相源；`.env` 中 `DEEPSEEK_API_KEY`/`QWEN_API_KEY`/`DOUBAO_API_KEY`/`LLM_API_KEY` 仅作 standalone/DB 故障兜底，已同步为 DB 有效值 |
| 例外标注 | `DASHSCOPE_API_KEY` 用于 RAG/意图向量化 embedding（`app.rag.embedding.api-key`），不走 DB，需在 `.env` 单独保持有效 |
| 模板 | `.env.template` 补充 provider key 说明与「DB 为真相源」注释 |

### 部署与验证

1. `mvn -pl chat-llm -am clean install -DskipTests` → `scp` jar → `bash /opt/app/restart-llm.sh all`
2. 验证：启动日志显示 `scheduling-1` 线程每 60 秒执行一次定时刷新（`定时刷新完成，DB 提供商 7 个`）
3. qwen 调用实测 `success:true`（592ms），观点辩论场恢复正常

### 影响

- 运维改 key：直接改 DB `llm_provider_props` 表，最长 60 秒内 chat-llm / chat-core 双端自动生效，**无需重启**
- 排查 LLM 401 时需同时检查 DB 与 `.env` 两处 key 是否一致，且注意 `DASHSCOPE_API_KEY`（embedding）是独立路径
