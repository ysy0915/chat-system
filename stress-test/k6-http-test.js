import http from 'k6/http';
import { check, sleep } from 'k6';

// 并发曲线可从 K6_STAGES 环境变量覆盖，格式 "30s:20,1m:50,1m:50,30s:0"
// （由 run-benchmark.sh 传入，用于基准/压力/稳定性三种场景复用同一脚本）
function parseStages(spec) {
  return spec.split(',').map((s) => {
    const [duration, target] = s.trim().split(':');
    return { duration, target: Number(target) };
  });
}

const DEFAULT_STAGES = [
  { duration: '30s', target: 20 },  // 30秒爬升至20并发
  { duration: '1m',  target: 50 },  // 1分钟爬升至50并发
  { duration: '1m',  target: 50 },  // 保持50并发1分钟
  { duration: '30s', target: 0 },   // 30秒降至0
];

export const options = {
  stages: __ENV.K6_STAGES ? parseStages(__ENV.K6_STAGES) : DEFAULT_STAGES,
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95%请求 < 2秒
    http_req_failed: ['rate<0.05'],    // 失败率 < 5%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERNAME = __ENV.USERNAME || 'testuser';
const PASSWORD = __ENV.PASSWORD || 'test123';
// 发送消息（完整 AI 链路）的迭代比例，默认 20%，其余迭代只打读接口
const SEND_RATIO = Number(__ENV.SEND_RATIO || 0.2);

// setup 只执行一次：登录获取 JWT 供所有 VU 共享。
// 重要：不要每迭代登录——auth/login 有敏感接口 IP 限流（10 次/分钟/IP，超限 429"操作过于频繁"），
// 压测机单 IP 高并发登录会直接触发限流，测不出真实链路。
export function setup() {
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  check(loginRes, { '登录 200': (r) => r.status === 200 });
  const token = loginRes.json('data.token') || loginRes.json('token');
  if (!token) {
    throw new Error(`登录失败，请检查账号或限流: ${loginRes.status} ${loginRes.body}`);
  }
  return { token };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };
  const params = { headers };

  // ---- 读接口（核心链路）----
  const recentRes = http.get(`${BASE_URL}/api/v1/messages/recent`, params);
  check(recentRes, { 'recent 200': (r) => r.status === 200 });

  const searchRes = http.get(`${BASE_URL}/api/v1/messages/search?keyword=${encodeURIComponent('你好')}&page=1&size=5`, params);
  check(searchRes, { 'search 200': (r) => r.status === 200 });

  const treeHoleRes = http.get(`${BASE_URL}/api/v1/treehole/recent`, params);
  check(treeHoleRes, { 'treehole 200': (r) => r.status === 200 });

  http.get(`${BASE_URL}/api/v1/messages/online-count?page=/`, params);

  // ---- 写接口（完整 AI 链路，按比例触发）----
  if (Math.random() < SEND_RATIO) {
    const reqId = `k6-${__VU}-${__ITER}-${Date.now()}`;
    const sendRes = http.post(`${BASE_URL}/api/v1/messages`,
      JSON.stringify({ req_id: reqId, question: '你好，请用一句话介绍你自己' }),
      params);
    check(sendRes, { 'send 202/200': (r) => r.status === 200 || r.status === 202 });
  }

  sleep(1);
}

export function handleSummary(data) {
  return {
    'stress-test/results/summary.json': JSON.stringify(data),
    stdout: {
      'http_req_duration_p95': data.metrics.http_req_duration.values['p(95)'],
      'http_req_failed_rate': data.metrics.http_req_failed.values.rate,
      'iterations': data.metrics.iterations.values.count,
    },
  };
}
