#!/usr/bin/env bash
#
# 一键压测脚本：封装基准/压力/稳定性三种场景，结果统一落到 results/ 目录，
# 便于「改架构前后」对比基线（优化前后各跑一次，diff 指标即可量化收益）。
#
# 用法：
#   ./stress-test/run-benchmark.sh baseline   # 基准测试（20→50 并发，3分钟，p95<2s）
#   ./stress-test/run-benchmark.sh stress     # 压力测试（100→500 并发，5分钟，p95<5s）
#   ./stress-test/run-benchmark.sh soak       # 稳定性测试（200 并发，30分钟，无泄漏）
#
# 环境变量（可选）：
#   BASE_URL   目标服务地址（默认 http://localhost:8080）
#   USERNAME   压测账号（默认 testuser，需系统内存在）
#   PASSWORD   压测账号密码（默认 test123）
#   SEND_RATIO 触发完整 AI 链路（真实调 LLM）的迭代比例（默认 0.2）
#
# 前置：
#   - 已安装 k6（brew install k6）
#   - 压测账号存在；单机高并发前建议先清限流 key（见 README.md）
set -euo pipefail

SCENARIO="${1:-baseline}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
USERNAME="${USERNAME:-testuser}"
PASSWORD="${PASSWORD:-test123}"
SEND_RATIO="${SEND_RATIO:-0.2}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
mkdir -p "$RESULTS_DIR"

# 时间戳用于区分每次压测结果，避免覆盖历史基线
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$RESULTS_DIR/${SCENARIO}-${TS}.json"

# 场景 → k6 stages 参数（通过环境变量 K6_STAGES 传入，脚本内按场景选择）
case "$SCENARIO" in
  baseline)
    STAGES="30s:20,1m:50,1m:50,30s:0"
    ;;
  stress)
    STAGES="1m:100,2m:500,2m:500,1m:0"
    ;;
  soak)
    # 稳定性：恒定 200 并发，30 分钟
    STAGES="30s:200,29m:200,30s:0"
    ;;
  *)
    echo "未知场景: $SCENARIO（可选 baseline/stress/soak）" >&2
    exit 1
    ;;
esac

echo "=============================================="
echo "压测场景 : $SCENARIO"
echo "目标地址 : $BASE_URL"
echo "并发曲线 : $STAGES"
echo "AI链路比例: $SEND_RATIO"
echo "结果文件 : $OUT"
echo "=============================================="

# 结果落盘：k6 的 handleSummary 默认写 stress-test/results/summary.json，
# 这里用环境变量让脚本知道本次时间戳，跑完后重命名保留为独立基线文件。
k6 run \
  --summary-export "$OUT" \
  -e BASE_URL="$BASE_URL" \
  -e USERNAME="$USERNAME" \
  -e PASSWORD="$PASSWORD" \
  -e SEND_RATIO="$SEND_RATIO" \
  --env K6_STAGES="$STAGES" \
  "$SCRIPT_DIR/k6-http-test.js" 2>&1 | tee "$RESULTS_DIR/${SCENARIO}-${TS}.log"

echo ""
echo "压测完成，结果已保存："
echo "  JSON: $OUT"
echo "  日志: $RESULTS_DIR/${SCENARIO}-${TS}.log"
