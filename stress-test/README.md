# 压测脚本

## 环境要求

安装 [k6](https://k6.io/docs/get-started/installation/)：

```bash
# macOS
brew install k6
# Linux
sudo apt-get install k6
```

## 运行压测

### 1. HTTP API 压测

```bash
# 本地
k6 run stress-test/k6-http-test.js

# 指定目标服务器
k6 run -e BASE_URL=http://your-nginx-ip:8081 stress-test/k6-http-test.js
```

### 2. WebSocket 压测

```bash
# 指定 WebSocket 地址
k6 run -e WS_URL=wss://example.com/ws stress-test/k6-ws-test.js
```

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
