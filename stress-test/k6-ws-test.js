import ws from 'k6/ws';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 50 },
    { duration: '1m',  target: 100 },
    { duration: '1m',  target: 100 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    ws_connecting: ['p(95)<5000'],
  },
};

// 注意：生产环境聊天通道是 STOMP over SockJS（前端 @stomp/stompjs + SockJS 接入），
// SockJS 握手协议 k6 不直接支持。本脚本用于：
//   1) 本地/测试环境有原生 WS 端点时压测基础设施层（连接建立、心跳、吞吐）；
//   2) 验证 Nginx /ws/ 反向代理握手（HTTP 101）是否正常。
// 生产 SockJS 链路建议用浏览器/websocat 手测，或对 broker 端点做 HTTP 层压测。
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws/chat?userId=1';

export default function () {
  const res = ws.connect(WS_URL, null, function (socket) {
    socket.on('open', () => {
      socket.send(JSON.stringify({ type: 'ping' }));
    });

    socket.on('message', (msg) => {
      // 接收消息
    });

    socket.on('close', () => {});

    socket.setTimeout(() => {
      socket.close();
    }, 30000);
  });

  check(res, { 'WS连接成功': (r) => r && r.status === 101 });
}
