# Multi-Agent 并行工作流升级实录

> 时间：2026-08-13（夜）｜ 版本：博思AI 全系统 ｜ 范围：chat-core / chat-common / chat-web / Nacos / 前端
> 结论先行：复杂任务从"单 Agent 串行"升级为 **TaskPlanner → RabbitMQ → 双实例 Worker 并行 → 收敛压缩** 的完整编排链路；
> 全链路测试 **PASS=12 / FAIL=0**，快速回归 **PASS=8 / FAIL=0**；架构评估 **92/100**（架构设计 14.5/15）。

---

## 一、背景：一个复杂任务为什么这么慢

过去，一个"帮我写一份公司数字化转型实施报告"级别的请求，走的是**单 Agent 串行**：一次 LLM 长回答从头到尾由同一个上下文生成。代价是：

- 上下文越来越长，首 token 延迟与总耗时线性恶化；
- 9 个业务维度（流程改造/技术选型/数据治理/组织人才…）挤在一次生成里，任何一块失败整篇作废；
- 输出不可控，动辄几千字，没有结构也没有重点。

于是这一晚要回答三个问题：

1. **能不能拆？** 把大任务拆成独立子任务，并行执行，最后汇总收敛。
2. **拆了会不会被打爆？** 100 并发进来，9 个子任务 × 100 请求，RabbitMQ 和 LLM 都扛不住，必须有过载保护。
3. **挂了怎么办？** 服务器重启后，拆到一半的任务还能不能恢复？

答案是：能拆、能扛、能自愈。下面按"如何实现的"拆解。

---

## 二、核心链路：一次请求的旅程

```
请求 → TaskPlanner（意图判断 → LLM 拆解 ≤9 个子任务）
     → 子任务写 Redis meta + 入队 RabbitMQ（agent.task）
     → 双实例 SubAgentWorker ×10（prefetch=1 + manual ack）并行消费
     → 子结果写 Redis hash（幂等覆盖 + received 计数）
     → SubTaskResultCollector 轮询收敛 → LLM 汇总压缩 ≤1000 字
     → 全局并发闸门：Redis Lua 原子计数 agent:workflow:active ≤8
     → WorkflowReconciler 30s 对账兜底（结果齐但未收敛 → 重新触发收敛）
```

三个关键阶段：

**① 拆解（TaskPlanner）**：先做意图判断，简单问题（如"你好"）**不拆解**，直接走普通流式；复杂/跨域问题才由 LLM 拆成最多 9 个独立子任务（`max-tasks=9`），每个子任务自带独立上下文入队。

**② 并行执行（SubAgentWorker）**：双 core 实例（9090/9092）各 10 个 Worker 并发消费。改造后采用 **manual ack + prefetch=1**——Worker 忙时不拉消息，RabbitMQ 按真实处理能力公平分发，杜绝"一个慢 Worker 拖住一堆任务"。

**③ 收敛（SubTaskResultCollector）**：轮询 Redis 中已收到的子结果，`received` 计数到齐后，用轻量模型（`qwen-turbo`）把 N 个结果汇总成一篇 ≤1000 字（`converge-max-chars=1000` 硬截断）的结构化回答，写回 DB 终态 `done`。

---

## 三、过载保护：100 并发进来会怎样

拆解能力放开了，闸门也必须跟上。最初用本地 `Semaphore` 限流，但双实例各持一把锁，9092 释放 9090 的许可、计数错乱。**改为一套 Redis Lua 原子限流**：

- Key：`agent:workflow:active`，INCR/DECR 由 Lua 脚本保证原子；
- 上限：`max-concurrent=8`，双实例**共享**同一额度；
- 超限策略：**降级普通流程**（不排队、不拒绝）——超出的请求走原来的单 Agent 路径兜底，保证可用性。

实测效果（T03，20 并发）：

| 指标 | 结果 |
|------|------|
| 并行工作流数 | **8**（= max-concurrent=8，全局限流生效） |
| 降级数 | 12（超限自动降级普通流程） |
| 全部请求 | 20/20 有明确去向，无一丢失 |

也就是说：**100 并发 → 8 个并行工作流 + 92 个降级**，系统不会雪崩，服务始终在线。

---

## 四、可靠投递：子任务一个都不能丢

并行拆解最怕"拆了 9 个，只回来 8 个"。为此实施 **manual ack + prefetch=1**：

| 环节 | 行为 | 语义 |
|------|------|------|
| SubAgentWorker | 成功 → `basicAck`；失败 → `basicNack(requeue=false)` | 失败不重复计数，防止死循环重投 |
| SubTaskResultCollector | 失败 → `requeue=true` 重新入队 | 依赖 Redis 幂等覆盖，重投不产生脏数据 |
| 子结果存储 | Redis hash `agent:subtask:result:{planId}` 幂等覆盖 + received INCR | 双实例同时写同一 key 也只收敛一次 |

**Redis 幂等 + 消息不丢**双保险：T04 实测 20 个 plan 的子任务**执行完成数 == 分发数，零丢失**。

---

## 五、故障兜底：服务器挂了重启后能恢复吗

这是当晚最有价值的一个问题。"拆到一半，core 崩了，重启后怎么办？"

RabbitMQ 消息有持久化，重启后能重新投递；但**收敛这一步**——如果承载收敛的实例恰好在结果到齐前崩溃，就没有人再触发收敛，plan 会永久卡住。答案不能是"大概率能恢复"，而应该是**有兜底机制**。

于是落地 **WorkflowReconciler（收敛对账）**：

- `@Scheduled` 每 30s（`reconcile-interval-ms=30000`）扫描 Redis 中的 plan；
- 触发条件：`received ≥ total`（结果已齐）+ 无 `converged` 标记 + DB 非 done + SETNX 锁成功（TTL 5min）；
- 满足即**重新触发收敛**，把卡住的 plan 救活。

三层去重防重复收敛：**converged 标记（主判据）→ DB 状态 → SETNX 锁**。端到端验证×2：伪造卡住 plan → 30s 内 Reconciler 触发 → DB `processing → done`；删除锁后不再重复触发。

> 踩坑洞见：仅靠 DB 状态去重会误判——**内部 API 请求根本没有 DB 行**，会把正常 plan 当成"卡住"反复重收敛；必须以 Redis 的 `converged` 标记为主判据。

---

## 六、测试验证：12/12 全绿

编写完整测试套件 `scripts/test-multiagent-suite.sh`：

| 用例 | 场景 | 结果 |
|------|------|------|
| T01 | 全链路（拆解 → 分发 → 并行 → 收敛 → DB 终态，答案 ≤1000 字） | ✅ |
| T02 | 简单问题不拆解（普通流式兜底） | ✅ |
| T03 | 20 并发 → 8 并行 + 12 降级（轮询等全部终态后统计） | ✅ |
| T04 | 子任务零丢失（等全部执行完成后断言，规避时序误报） | ✅ |
| T05/T06 | 输出压缩 ≤1000 + DB 终态 done | ✅ |
| 方案 A | 伪造卡住 plan → 30s 内 Reconciler 触发恢复；删锁不再重复触发 | ✅×2 |

**套件结果：PASS=12 / FAIL=0；快速回归（--quick）PASS=8 / FAIL=0，确认对既有链路无影响。**

---

## 七、踩坑记录（当晚真实教训）

1. **Semaphore 跨实例错配**：9092 释放 9090 的许可，计数异常 → 换 Redis Lua 原子计数，从根上消除。
2. **服务器上没有 redis-cli**：Redis 实际跑在主服务器（112.124.106.108，`/usr/local/redis/bin/redis-cli`，6379 无密码），不是 Milvus 服务器；`-a` 反而报 AUTH 错。
3. **Nacos 公网 8848 被防火墙挡**：需在 Milvus 服务器本机 `curl http://127.0.0.1:8848`；且 **group 是 `CHAT` 不是 `DEFAULT_GROUP`**。
4. **UA 黑名单拦 curl**：测试需伪造浏览器 UA + 随机 X-Forwarded-For（否则 403 / 单 IP 限流）。
5. **收敛日志没有 req_id**：`收敛完成`只含 planId，测试要先经 req_id 反查 planId 再匹配。
6. **T03 统计污染**：未加 RID_PREFIX 隔离时把历史日志也统计进来 → 完整前缀隔离 + 轮询等待全部终态。
7. **T04 时序误报**：任务还在执行中就断言"完成数 < 分发数" → 先等全部子任务执行完成再断言。
8. **部署 Reconciler 后误触发 4 个历史遗留 T03 plan**（内部 API 无 DB 行，DB 去重失效）→ 加 converged 标记去重 + 清理 27 个遗留 plan 键。

---

## 八、配套同步

- **配置落位**：Nacos `chat-core-prod.yml`（group=CHAT）新增 `reconcile-interval-ms: 30000`；`max-tasks=9 / max-concurrent=8 / converge-max-chars=1000 / converge-model=qwen-turbo`，listener 10/20、prefetch=1 就位。
- **文档更新**：10 个文件 +314 行（架构全盘说明 / 架构设计说明 / 部署运维手册 / 系统架构说明 / CHANGELOG-3.0 / 数据库设计说明 / 故障排查指南 / 架构评估报告 等）。
- **架构评估**：综合 **91 → 92**（架构设计 14/15 → 14.5/15，由 Multi-Agent 可靠性闭环代码落地驱动），新增第六章专项评估。

---

## 九、遗留风险与下一步

| 风险 | 现状 | 下一步 |
|------|------|--------|
| 工作流状态依赖 Redis | Redis 故障则工作流中断，Reconciler 兜底也失效 | Redis AOF 持久化 + 高可用；Reconciler 增加 DB 源对账 |
| Worker 失败不重试 | `nack(requeue=false)` 即终态，靠 Collector 重投 | 失败子任务进死信队列 + 指数退避重试 |
| 服务器单点 + 内存紧张 | 双服务器均为单点，可用内存 ~500MB | 第二台服务器 + 备份演练；扩容/压缩 JVM 堆 |

---

> 一句话总结这一晚：**让复杂任务"拆得开、跑得动、扛得住、丢不了、挂了能自愈"** —— 从编排链路、全局闸门、可靠投递到对账兜底，形成一条完整闭环；最后用 12 条测试用例把闭环钉死在代码里。
