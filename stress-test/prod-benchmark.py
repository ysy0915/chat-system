#!/usr/bin/env python3
"""
生产环境真实压测脚本（1000 并发，本地生成 JWT 绕过登录限流）
------------------------------------------------------------
针对生产环境安全加固（JWT 鉴权 + 用户级限流 20 次/分钟 + UA 过滤），
实现「本地签发 JWT + 并发发消息」的真实链路压测。

前置：
1. 已 DB 批量造 1000 个测试账号（name=bench_0001~bench_1000，password=Bench@123456）
2. 账号 id 连续（从 UID_BASE 起），脚本据此本地生成 JWT（HS256），无需登录

原理：
- JWT 密钥来自生产 .env 的 JWT_SECRET，本地用 hmac 签发 HS256 token
- 每个并发用户用独立 token（独立 uid），绕过 20 次/分钟 的用户级消息限流
- 合法浏览器 UA，绕过 IpRateLimitInterceptor 的爬虫 UA 过滤

用法:
  python3 stress-test/prod-benchmark.py --users=1000 --rampup=30

选项:
  --users     并发用户数（默认 1000）
  --rampup    爬坡秒数（默认 30，0 表示瞬间全量）
  --ai        触发 AI 回答（默认 false；true 会打真实 LLM，高费用）
  --message   消息内容
  --uid-base  账号起始 uid（默认 5250，即 bench_0001 的 uid）
"""

import asyncio
import time
import sys
import json
import uuid
import random
import base64
import hmac
import hashlib
import argparse
from collections import Counter

try:
    import httpx
except ImportError:
    print("请安装 httpx: pip install httpx")
    sys.exit(1)

# 生产 JWT 密钥（与服务器 .env JWT_SECRET 一致，用于本地签发测试 token）
JWT_SECRET = "k8Xp2mQ7vR3nF9wL5tY1bJ6cH4dA0eG8iS2uW7xZ"

BROWSER_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_jwt(sub: str, uid: int, role: str = "user") -> str:
    """本地签发 HS256 JWT（与后端 JwtUtil.generateToken 完全一致）"""
    now = int(time.time())
    exp = now + 86400  # 24h
    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"sub": sub, "uid": uid, "role": role, "iat": now, "exp": exp}
    h = b64url(json.dumps(header, separators=(",", ":")).encode())
    p = b64url(json.dumps(payload, separators=(",", ":")).encode())
    signing_input = f"{h}.{p}".encode()
    sig = hmac.new(JWT_SECRET.encode(), signing_input, hashlib.sha256).digest()
    return f"{h}.{p}.{b64url(sig)}"


class Stats:
    def __init__(self):
        self.message = {"ok": 0, "fail": 0, "times": []}
        self.errors = []
        self.status_counter = Counter()

    def record(self, ok, elapsed_ms, status, err=""):
        self.status_counter[status] += 1
        if ok:
            self.message["ok"] += 1
            self.message["times"].append(elapsed_ms)
        else:
            self.message["fail"] += 1
            if err and len(self.errors) < 30:
                self.errors.append(f"[{status}] {err}")

    def report(self, total_elapsed):
        m = self.message
        print("\n" + "=" * 66)
        print("📊 生产环境压测结果（本地签发 JWT，1000 并发）")
        print("=" * 66)
        total = m["ok"] + m["fail"]
        print(f"  消息请求: {m['ok']} 成功 / {m['fail']} 失败 ({m['ok'] / max(total, 1) * 100:.1f}%)")
        print(f"  HTTP 状态分布: {dict(self.status_counter.most_common())}")
        if m["times"]:
            t = sorted(m["times"])
            n = len(t)
            print(f"  响应时间: 最小 {t[0]:.0f}ms | P50 {t[n // 2]:.0f}ms | "
                  f"P90 {t[int(n * 0.9)]:.0f}ms | P99 {t[int(n * 0.99)]:.0f}ms | "
                  f"最大 {t[-1]:.0f}ms | 平均 {sum(t) / n:.0f}ms")
        print(f"  吞吐量: {total / max(total_elapsed, 1):.1f} req/s（总耗时 {total_elapsed:.1f}s）")
        if self.errors:
            print(f"\n  错误详情 (前 15):")
            for err, cnt in Counter(self.errors).most_common(15):
                print(f"    [{cnt}x] {err[:100]}")
        print("=" * 66 + "\n")


async def send_message(client, host, token, message, ai_answer, stats):
    headers = {
        "User-Agent": BROWSER_UA,
        "Content-Type": "application/json",
        "Authorization": f"Bearer {token}",
    }
    body = {
        "req_id": str(uuid.uuid4()),
        "question": message,
        "private": "true",
        "ai_answer": "true" if ai_answer else "false",
    }
    start = time.time()
    try:
        r = await client.post(f"{host}/api/v1/messages", json=body, headers=headers)
        elapsed = (time.time() - start) * 1000
        ok = r.status_code in (200, 202)
        stats.record(ok, elapsed, r.status_code, "" if ok else r.text[:80])
    except Exception as e:
        elapsed = (time.time() - start) * 1000
        stats.record(False, elapsed, 0, f"{type(e).__name__}: {e}")


async def worker(client, host, uid, message, ai_answer, stats):
    sub = f"bench_{uid - 5250 + 1:04d}@loadtest.local"
    token = make_jwt(sub, uid)
    await send_message(client, host, token, message, ai_answer, stats)


async def run(args):
    stats = Stats()
    print(f"""
╔══════════════════════════════════════════════════════════╗
║        🔥 生产环境 {args.users} 并发压测（本地签发 JWT）      ║
╠══════════════════════════════════════════════════════════╣
║  目标:      {args.host:<42} ║
║  并发用户:  {args.users}（独立 uid，绕过用户级限流）
║  AI 回答:   {'是 ⚠️ 高费用' if args.ai else '否（仅消息写入）'}
║  爬坡:      {args.rampup}s
╚══════════════════════════════════════════════════════════╝
""")
    if args.ai:
        print("⚠️  --ai 会触发真实 LLM 调用，将产生 API 费用！建议先小规模验证。\n")

    test_start = time.time()
    limits = httpx.Limits(max_keepalive_connections=100, max_connections=300)
    async with httpx.AsyncClient(timeout=30.0, limits=limits) as client:
        tasks = []
        for i in range(args.users):
            uid = args.uid_base + i
            tasks.append(worker(client, args.host, uid, args.message, args.ai, stats))

        if args.rampup > 0:
            # 分批爬坡
            batch = max(1, args.users // max(1, int(args.rampup)))
            batches = [tasks[i:i + batch] for i in range(0, len(tasks), batch)]
            done = 0
            for bt in batches:
                await asyncio.gather(*bt)
                done += len(bt)
                print(f"\r  进度: {done}/{args.users} | 成功 {stats.message['ok']} | "
                      f"失败 {stats.message['fail']}", end="", flush=True)
                await asyncio.sleep(1)
        else:
            await asyncio.gather(*tasks)

    total_elapsed = time.time() - test_start
    stats.report(total_elapsed)


def main():
    p = argparse.ArgumentParser(description="生产环境真实压测（本地签发 JWT）")
    p.add_argument("--host", default="http://112.124.106.108")
    p.add_argument("--users", type=int, default=1000)
    p.add_argument("--rampup", type=float, default=30.0)
    p.add_argument("--ai", action="store_true")
    p.add_argument("--message", default="你好，请用一句话介绍你自己。")
    p.add_argument("--uid-base", type=int, default=5250)
    args = p.parse_args()
    args.host = args.host.rstrip("/")
    asyncio.run(run(args))


if __name__ == "__main__":
    main()
