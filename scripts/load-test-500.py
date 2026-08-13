#!/usr/bin/env python3
"""
500 并发用户压测脚本
-----------------------
模拟 500 个用户同时发送私聊消息，测试系统稳定性。

用法:
  pip install httpx websockets
  python scripts/load-test-500.py [--host=<host>] [--users=<N>] [--ai] [--rampup=<seconds>]

选项:
  --host     目标地址 (默认: http://your-nginx-ip)
  --users    并发用户数 (默认: 500)
  --ai       是否触发 AI 回答 (默认: 仅插入消息, ai_answer=false)
  --rampup   爬坡时间秒数 (默认: 30, 用户逐步上线)
  --light    轻量模式: 仅 HTTP POST, 不连 WebSocket

示例:
  python scripts/load-test-500.py --users=100                 # 100 用户，仅消息写入
  python scripts/load-test-500.py --users=100 --ai            # 100 用户，触发 AI 回答
  python scripts/load-test-500.py --users=50 --ai --rampup=60 # 50 用户，60秒爬坡
  python scripts/load-test-500.py --users=500 --light         # 500 用户，纯 HTTP 压测
"""

import asyncio
import time
import sys
import os
import json
import uuid
import random
import argparse
from dataclasses import dataclass, field
from typing import Optional

try:
    import httpx
except ImportError:
    print("请安装 httpx: pip install httpx")
    sys.exit(1)

# ====================== 配置 ======================

@dataclass
class Config:
    host: str = "http://your-nginx-ip"
    user_count: int = 500
    ai_answer: bool = False       # 是否触发 AI 回答
    rampup_seconds: float = 30.0   # 爬坡时间
    light_mode: bool = False       # 纯 HTTP 模式
    timeout: float = 60.0          # 单个请求超时
    yes: bool = False              # 跳过确认
    message: str = "你好，能帮我分析一下今天的新闻吗？"


# ====================== 统计 ======================

@dataclass
class Stats:
    total: int = 0
    success: int = 0
    failed: int = 0
    response_times: list = field(default_factory=list)
    errors: list = field(default_factory=list)

    def record(self, ok: bool, elapsed: float, error: str = ""):
        self.total += 1
        if ok:
            self.success += 1
            self.response_times.append(elapsed)
        else:
            self.failed += 1
            if error and len(self.errors) < 20:  # 最多保留 20 条错误
                self.errors.append(error)

    def report(self):
        print("\n" + "=" * 60)
        print("📊 压测结果")
        print("=" * 60)
        print(f"  总请求数:    {self.total}")
        print(f"  成功:        {self.success} ({self.success/max(self.total,1)*100:.1f}%)")
        print(f"  失败:        {self.failed} ({self.failed/max(self.total,1)*100:.1f}%)")

        if self.response_times:
            times = sorted(self.response_times)
            n = len(times)
            print(f"  响应时间:")
            print(f"    最小:      {times[0]:.0f}ms")
            print(f"    P50:       {times[n//2]:.0f}ms")
            print(f"    P90:       {times[int(n*0.9)]:.0f}ms")
            print(f"    P99:       {times[int(n*0.99)]:.0f}ms")
            print(f"    最大:      {times[-1]:.0f}ms")
            print(f"    平均:      {sum(times)/n:.0f}ms")

        if self.errors:
            print(f"\n  错误类型 (前 {len(self.errors)} 条):")
            from collections import Counter
            for err, count in Counter(self.errors).most_common(10):
                print(f"    [{count}x] {err[:100]}")

        print("=" * 60 + "\n")


# ====================== 单个用户行为 ======================

def random_ip():
    """生成随机公网 IP"""
    return f"{random.randint(1, 223)}.{random.randint(0, 255)}.{random.randint(0, 255)}.{random.randint(1, 254)}"


async def send_message(config: Config, stats: Stats, user_index: int):
    """单个用户：发送 POST /api/v1/messages"""
    user_id = 10000 + user_index  # 使用不同的 user_id 避免触达用户级限流
    req_id = str(uuid.uuid4())
    fake_ip = random_ip()
    start = time.time()

    headers = {
        "Content-Type": "application/json",
        "User-Agent": "LoadTest/1.0 (macOS) PerformanceBenchmark",
        "X-Request-Id": req_id,
        "X-Forwarded-For": fake_ip,
        "X-Real-IP": fake_ip,
    }

    body = {
        "req_id": req_id,
        "user_id": user_id,
        "question": config.message,
        "private": "true",
        "ai_answer": "true" if config.ai_answer else "false",
    }

    ok = False
    error_msg = ""
    elapsed = 0

    try:
        # 限制连接池，避免耗尽端口
        async with httpx.AsyncClient(timeout=config.timeout, limits=httpx.Limits(
            max_keepalive_connections=20,
            max_connections=100,
        )) as client:
            resp = await client.post(
                f"{config.host}/api/v1/messages",
                json=body,
                headers=headers,
            )
            elapsed = (time.time() - start) * 1000

            if resp.status_code in (200, 202):
                data = resp.json()
                status = data.get("status", "")
                if status in ("queued", "ok", "success"):
                    ok = True
                else:
                    error_msg = f"unexpected status={status}"
            elif resp.status_code == 429:
                data = resp.json()
                retry = data.get("retry_after", "?")
                error_msg = f"429 rate-limited (retry_after={retry}s)"
            else:
                error_msg = f"HTTP {resp.status_code}: {resp.text[:100]}"

    except httpx.TimeoutException:
        elapsed = (time.time() - start) * 1000
        error_msg = "timeout"
    except httpx.ConnectError as e:
        elapsed = (time.time() - start) * 1000
        error_msg = f"connect: {e}"
    except Exception as e:
        elapsed = (time.time() - start) * 1000
        error_msg = f"{type(e).__name__}: {e}"

    stats.record(ok, elapsed, error_msg)
    return ok


# ====================== 主控制 ======================

async def run_load_test(config: Config):
    stats = Stats()
    print(f"""
╔══════════════════════════════════════════════════════╗
║           🔥 500 并发用户压测                         ║
╠══════════════════════════════════════════════════════╣
║  目标:      {config.host:<40} ║
║  用户数:    {config.user_count:<40} ║
║  AI 回答:   {'是 (ai_answer=true) ⚠️ 高负载' if config.ai_answer else '否 (ai_answer=false) 仅消息写入'}{' ' * (24 - (31 if config.ai_answer else 36))} ║
║  爬坡时间:  {config.rampup_seconds:.0f}s{' ' * 31} ║
║  超时:      {config.timeout:.0f}s{' ' * 33} ║
╚══════════════════════════════════════════════════════╝
""")

    if config.ai_answer:
        print("⚠️  警告: ai_answer=true 将触发真实 LLM 调用！")
        print(f"    500 个并发 LLM 调用会导致: 大量 API 费用 + 严重延迟")
        print(f"    建议先用 --users=20 --ai --rampup=30 小规模测试")
        if not config.yes:
            try:
                confirm = input("\n确认继续? (yes/no): ")
                if confirm.lower() != "yes":
                    print("已取消")
                    return
            except EOFError:
                print("\n⚠️  非交互模式，自动确认继续")
        else:
            print("\n✅ --yes 自动确认继续")

    print(f"\n🚀 开始压测... 用户逐步上线 ({config.rampup_seconds:.0f}s 爬坡)\n")

    # 进度显示
    async def progress_reporter():
        while stats.total < config.user_count:
            await asyncio.sleep(2)
            rate = stats.total / max(time.time() - test_start, 1)
            print(f"\r  进度: {stats.total}/{config.user_count} "
                  f"| 成功: {stats.success} | 失败: {stats.failed} | "
                  f"速率: {rate:.1f}/s  ", end="", flush=True)
        print()

    test_start = time.time()
    progress_task = asyncio.create_task(progress_reporter())

    # 爬坡：将用户均匀分布在 rampup_seconds 内启动
    tasks = []
    for i in range(config.user_count):
        if config.rampup_seconds > 0:
            delay = (i / config.user_count) * config.rampup_seconds
            tasks.append((delay, i))
        else:
            tasks.append((0, i))

    # 按延时排序执行
    tasks.sort(key=lambda x: x[0])

    # 并发执行所有任务
    coroutines = []
    for delay, i in tasks:
        async def worker(d=delay, idx=i):
            await asyncio.sleep(d)
            await send_message(config, stats, idx)
        coroutines.append(worker())

    await asyncio.gather(*coroutines)
    progress_task.cancel()

    total_elapsed = time.time() - test_start
    print(f"\n  总耗时: {total_elapsed:.1f}s")
    print(f"  吞吐量: {stats.total/total_elapsed:.1f} req/s")

    stats.report()

    # 健康检查
    print("🔍 检查服务健康状态...")
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                f"{config.host}/api/v1/auth/me",
                headers={"User-Agent": "LoadTest/1.0"}
            )
            print(f"  服务状态: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  服务可达性检查: {type(e).__name__}")

    return stats


# ====================== 预热检查 ======================

async def preflight_check(config: Config):
    """压测前检查服务可达性"""
    print("🔍 预热检查...")
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            resp = await client.get(
                f"{config.host}/api/v1/auth/me",
                headers={"User-Agent": "LoadTest/1.0"}
            )
            print(f"  服务可达: HTTP {resp.status_code}")
    except Exception as e:
        print(f"  ⚠️  连接失败: {e}")
        print(f"  请确认服务 {config.host} 可以访问")
        if input("继续? (yes/no): ").lower() != "yes":
            sys.exit(1)

    # 发送一条测试消息
    print("  发送测试消息...")
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                f"{config.host}/api/v1/messages",
                json={
                    "req_id": str(uuid.uuid4()),
                    "user_id": 99999,
                    "question": "ping",
                    "private": "true",
                    "ai_answer": "false",
                },
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": "LoadTest/1.0",
                },
            )
            if resp.status_code in (200, 202):
                print(f"  ✅ 测试消息发送成功 (HTTP {resp.status_code})")
            else:
                print(f"  ⚠️  测试消息返回: HTTP {resp.status_code} {resp.text[:100]}")
    except Exception as e:
        print(f"  ⚠️  测试消息失败: {e}")


# ====================== 入口 ======================

def main():
    parser = argparse.ArgumentParser(
        description="500 并发用户压测",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--host", default="http://your-nginx-ip", help="目标地址")
    parser.add_argument("--users", type=int, default=500, help="并发用户数")
    parser.add_argument("--ai", action="store_true", help="触发 AI 回答")
    parser.add_argument("--light", action="store_true", help="纯 HTTP 模式")
    parser.add_argument("--rampup", type=float, default=30.0, help="爬坡秒数")
    parser.add_argument("--timeout", type=float, default=60.0, help="请求超时秒数")
    parser.add_argument("--skip-check", action="store_true", help="跳过预热检查")
    parser.add_argument("--yes", action="store_true", help="跳过 AI 确认提示")

    args = parser.parse_args()

    config = Config(
        host=args.host.rstrip("/"),
        user_count=args.users,
        ai_answer=args.ai,
        light_mode=args.light,
        rampup_seconds=args.rampup,
        timeout=args.timeout,
        yes=args.yes,
    )

    asyncio.run(async_main(config, args.skip_check))


async def async_main(config: Config, skip_check: bool):
    if not skip_check:
        await preflight_check(config)
    await run_load_test(config)


if __name__ == "__main__":
    main()
