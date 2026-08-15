# 压测脚本

## 环境要求

安装 [k6](https://k6.io/docs/get-started/installation/)：

```bash
# macOS
brew install k6
# Linux
sudo apt-get install k6
```

## 前置准备

- 准备压测账号：`-e USERNAME=xx -e PASSWORD=xx`（默认 testuser/test123，需在系统内存在）。
- 登录有 IP 限流（敏感接口 10 次/分钟/IP，超限 429"操作过于频繁"），脚本已在 `setup()` 中
  登录一次共享 JWT，**不要改成每迭代登录**。
- 全站限流 600 次/分钟/IP，60 秒超 1000 次自动拉黑 10 分钟——单机高并发压测（>600 req/min）
  会触发 429/拉黑，建议多台压测机或压测前清限流 key：
  `del ip:rate:* rate:login-fail:*`（Redis 内 `/api/v1/chat` 之外路径）。
- 压测发送消息会真实调用 LLM（花钱+耗时），默认只有 20% 迭代触发，可用 `-e SEND_RATIO=0` 关闭。

## 运行压测

### 1. HTTP API 压测

```bash
# 本地
k6 run stress-test/k6-http-test.js

# 指定目标服务器 + 账号
k6 run -e BASE_URL=http://112.124.106.108 \
       -e USERNAME=your_user -e PASSWORD=your_pwd \
       stress-test/k6-http-test.js

# 只测读接口（不触发 LLM）
k6 run -e SEND_RATIO=0 stress-test/k6-http-test.js
```

### 2. WebSocket 压测

生产聊天通道为 STOMP over SockJS，k6 不直接支持 SockJS 握手；本脚本仅用于
验证原生 WS 端点/反向代理握手（HTTP 101）：

```bash
k6 run -e WS_URL=ws://localhost:8080/ws/chat?userId=1 stress-test/k6-ws-test.js
```

生产 SockJS 链路建议用浏览器 DevTools / websocat 手测，或对 HTTP 层做压测。

### 3. 结果查看

- 实时输出: 终端会显示实时 TPS / P95 延迟 / 失败率
- JSON 报告: `stress-test/results/summary.json`

## 压测矩阵

| 场景 | 并发 | 持续时间 | 目标 |
|------|:---:|:---:|------|
| 基准测试 | 20→50→0 | 3分钟 | p95 < 2s |
| 压力测试 | 100→500→0 | 5分钟 | p95 < 5s, 错误率 < 1% |
| 稳定性测试 | 200 | 30分钟 | 无内存泄漏, 错误率 < 0.1% |

## 关键指标

- **TPS**: 每秒处理请求数
- **P95延迟**: 95%请求完成时间
- **错误率**: 失败请求占比
- **WS连接成功率**: WebSocket 握手成功率

## 配套 DDL（可选，低峰期执行）

`docs/db-migrations/V1.2.0__add_reqid_unique_and_fulltext_indexes.sql`：
messages.req_id 唯一索引（幂等去重）+ content ngram 全文索引（搜索优化，需 MySQL 8.0+）。
执行前先查重：`SELECT req_id, COUNT(*) c FROM messages GROUP BY req_id HAVING c > 1;`
