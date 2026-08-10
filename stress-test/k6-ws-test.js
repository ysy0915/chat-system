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

const WS_URL = __ENV.WS_URL || 'wss://example.com/ws';

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
