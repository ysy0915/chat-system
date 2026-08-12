#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生产环境 Prometheus 告警 Webhook 接收端（Milvus 服务器 121.40.188.98）
- 监听 0.0.0.0:9950，接收 Alertmanager webhook POST
- 可选推送到钉钉（设置环境变量 DINGTALK_WEBHOOK）
- 日志输出到 stdout（由 health-check.sh 守护，重定向到 /opt/app/logs/prometheus-alerts.log）

启动（必须 setsid nohup 防 ssh 会话杀死）：
  setsid nohup python3 /opt/app/prometheus/alert-webhook.py \
    >> /opt/app/logs/prometheus-alerts.log 2>&1 &
"""
import json
import os
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

DINGTALK_WEBHOOK = os.environ.get("DINGTALK_WEBHOOK", "")


def send_dingtalk(title: str, text: str) -> None:
    """推送钉钉机器人消息（如配置了 DINGTALK_WEBHOOK）"""
    if not DINGTALK_WEBHOOK:
        return
    payload = json.dumps({
        "msgtype": "markdown",
        "markdown": {"title": title, "text": text}
    }).encode("utf-8")
    req = urllib.request.Request(
        DINGTALK_WEBHOOK, data=payload,
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp.read()
    except Exception as exc:  # 推送失败不影响告警落盘
        print(f"[webhook] 钉钉推送失败: {exc}", flush=True)


class HookHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body.decode("utf-8"))
        except Exception as exc:
            print(f"[webhook] JSON 解析失败: {exc}", flush=True)
            self.send_response(400)
            self.end_headers()
            return

        alerts = data.get("alerts", [])
        now = time.strftime("%Y-%m-%d %H:%M:%S")
        for alert in alerts:
            labels = alert.get("labels", {})
            annotations = alert.get("annotations", {})
            name = labels.get("alertname", "unknown")
            status = alert.get("status", "firing")
            severity = labels.get("severity", "none")
            summary = annotations.get("summary", "")
            desc = annotations.get("description", "")
            line = (f"[{now}] [{status}] {name} severity={severity} "
                    f"job={labels.get('job', '-')} instance={labels.get('instance', '-')} "
                    f"| {summary} | {desc}")
            print(line, flush=True)
            if DINGTALK_WEBHOOK:
                send_dingtalk(
                    f"【{'告警恢复' if status == 'resolved' else '告警'}】{name}",
                    f"**{name}** ({severity})\n- 状态: {status}\n- 实例: {labels.get('instance', '-')}\n- 说明: {desc}")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok": true}')

    def log_message(self, fmt, *args):  # 精简 access log
        pass


if __name__ == "__main__":
    server = HTTPServer(("0.0.0.0", 9950), HookHandler)
    print("[webhook] alert-webhook listening on :9950", flush=True)
    server.serve_forever()
