import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },  // 30秒爬升至20并发
    { duration: '1m',  target: 50 },  // 1分钟爬升至50并发
    { duration: '1m',  target: 50 },  // 保持50并发1分钟
    { duration: '30s', target: 0 },   // 30秒降至0
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95%请求 < 2秒
    http_req_failed: ['rate<0.05'],    // 失败率 < 5%
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // 1. 登录获取 JWT
  const loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username: 'testuser',
    password: 'test123',
  }), { headers: { 'Content-Type': 'application/json' } });

  // 2. 获取消息列表
  const msgRes = http.get(`${BASE_URL}/api/v1/messages?user_id=1`);

  // 3. 树洞历史
  const treeHoleRes = http.get(`${BASE_URL}/api/v1/treehole/recent`);

  // 4. 在线人数
  http.get(`${BASE_URL}/api/v1/messages/online-count?page=/`);

  check(msgRes, { '消息列表 200': (r) => r.status === 200 });
  check(treeHoleRes, { '树洞 200': (r) => r.status === 200 });

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
