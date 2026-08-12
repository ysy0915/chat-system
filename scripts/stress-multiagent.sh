#!/usr/bin/env bash
# 并发压测：并发发送 N 个复杂请求，验证信号量限流降级
# 用法: ./stress.sh <并发数> <起始序号>
set -e
CONCURRENCY=${1:-20}
START=${2:-20}
CORE_URL="http://127.0.0.1:9090/internal/chat/process"

QUESTION="请制定一份公司2026年全面数字化转型实施报告，涵盖：1业务流程数字化改造、2技术架构选型、3数据治理体系建设、4组织与人才转型、5供应链协同数字化、6营销渠道数字化、7信息安全与合规、8项目实施路线图、9投入产出评估。各部分都要给出具体建议"

echo "开始并发压测: $CONCURRENCY 并发, 起始序号 $START, 时间 $(date +%H:%M:%S)"

pids=()
for i in $(seq 1 "$CONCURRENCY"); do
  n=$((START + i - 1))
  req_id="stress-${n}"
  (
    resp=$(curl -s -m 10 -X POST "$CORE_URL" -H 'Content-Type: application/json' \
      -d "{\"req_id\":\"${req_id}\",\"user_id\":5231,\"private\":\"true\",\"question\":\"${QUESTION}\",\"preferred_model_config_id\":2}")
    echo "[$req_id] $(date +%H:%M:%S.%3N) -> $resp"
  ) &
  pids+=($!)
done

# 等待所有请求完成
for pid in "${pids[@]}"; do
  wait "$pid"
done

echo "全部请求已发送, 时间 $(date +%H:%M:%S)"
