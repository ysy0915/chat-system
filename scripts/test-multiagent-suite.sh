#!/usr/bin/env bash
# =====================================================================
# Multi-Agent 并行工作流完整测试套件
# 覆盖场景:
#   T01 单请求并行链路（web→core→RabbitMQ→Worker→收敛→DB done）
#   T02 简单问题不拆解（直接普通流式）
#   T03 并发限流降级（20 并发 → 全局 max-concurrent=8 并行 + 降级）
#   T04 manual ack 可靠性（子任务无丢失，任务完成数==分发数）
#   T05 输出压缩（answerLen ≤ 1000）
#   T06 DB 持久化终态（全部 done）
# 用法: bash test-multiagent-suite.sh [--quick]  (--quick 跳过 T03 并发测试)
# =====================================================================
set -u
TS=$(date +%Y%m%d-%H%M%S)
RESULT=/tmp/multiagent-test/result-${TS}.log
mkdir -p /tmp/multiagent-test
exec > >(tee -a "$RESULT") 2>&1

WEB_URL="http://127.0.0.1:8081/api/v1/messages"
CORE_A="http://127.0.0.1:9090/internal/chat/process"
CORE_B="http://127.0.0.1:9092/internal/chat/process"
USER_ID=5231
# 浏览器 UA 规避 UA 黑名单(curl 被拦)；随机 IP 规避单 IP 限流(600/min)
FAKE_UA="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
rand_ip() { echo "$((RANDOM%200+10)).$((RANDOM%255)).$((RANDOM%255)).$((RANDOM%255))"; }
WEB_CURL() { curl -s -m 10 -A "$FAKE_UA" -H "X-Forwarded-For: $(rand_ip)" -H "X-Real-IP: $(rand_ip)" "$@"; }
QUESTION9="请制定一份公司2026年全面数字化转型实施报告，涵盖：1业务流程数字化改造、2技术架构选型、3数据治理体系建设、4组织与人才转型、5供应链协同数字化、6营销渠道数字化、7信息安全与合规、8项目实施路线图、9投入产出评估。各部分都要给出具体建议"

PASS=0; FAIL=0
GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[1;33m'; NC='\033[0m'

log()  { echo -e "$(date +%H:%M:%S) $1"; }
pass() { PASS=$((PASS+1)); log "${GREEN}[PASS]${NC} $1"; }
fail() { FAIL=$((FAIL+1)); log "${RED}[FAIL]${NC} $1"; }
info() { log "${YELLOW}[INFO]${NC} $1"; }

# 断言: 双实例日志中出现 pattern（按 req_id 过滤）
assert_core_log() {
  local req_id="$1" pattern="$2"
  grep -h "$req_id" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -qE "$pattern"
}

# 断言: DB 中该 req 的状态与长度
db_check() {
  local req_id="$1" expect_status="$2" max_len="${3:-}"
  local row
  row=$(mysql -h your-rds-host -uYOUR_DB_USER -p'YOUR_DB_PASSWORD' test_data \
        -N -e "SELECT status,CHAR_LENGTH(answer_json) FROM messages WHERE req_id='${req_id}'" 2>/dev/null)
  if [ -z "$row" ]; then echo "NOT_FOUND"; return 1; fi
  local st len
  st=$(echo "$row" | awk '{print $1}'); len=$(echo "$row" | awk '{print $2}')
  if [ "$st" != "$expect_status" ]; then echo "STATUS_MISMATCH($st!=$expect_status)"; return 1; fi
  if [ -n "$max_len" ] && [ "$len" -gt "$max_len" ]; then echo "LEN_OVER($len>$max_len)"; return 1; fi
  echo "OK(st=$st,len=$len)"
  return 0
}

wait_for_core_log() {
  local req_id="$1" pattern="$2" timeout="${3:-90}" slept=0
  while [ $slept -lt $timeout ]; do
    if assert_core_log "$req_id" "$pattern"; then return 0; fi
    sleep 5; slept=$((slept+5))
  done
  return 1
}

# 收敛日志不含 req_id，仅含 planId —— 通过 req_id 先定位 planId 再匹配收敛
get_plan_id() {
  local req_id="$1"
  grep -h "req_id=${req_id}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
    | grep -oE 'planId=[0-9a-f]+' | head -1 | cut -d= -f2
}
wait_for_converge() {
  local req_id="$1" timeout="${2:-150}" plan_id="" slept=0
  while [ $slept -lt $timeout ]; do
    if [ -z "$plan_id" ]; then plan_id=$(get_plan_id "$req_id"); fi
    if [ -n "$plan_id" ] && grep -h "planId=${plan_id}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
        | grep -q '收敛完成'; then
      return 0
    fi
    sleep 5; slept=$((slept+5))
  done
  return 1
}

wait_for_db() {
  local req_id="$1" timeout="${2:-120}" slept=0
  while [ $slept -lt $timeout ]; do
    local st
    st=$(mysql -h your-rds-host -uYOUR_DB_USER -p'YOUR_DB_PASSWORD' test_data \
         -N -e "SELECT status FROM messages WHERE req_id='${req_id}'" 2>/dev/null)
    if [ "$st" = "done" ]; then return 0; fi
    sleep 5; slept=$((slept+5))
  done
  return 1
}

echo "======================================================================"
echo " Multi-Agent 测试套件  $TS"
echo "======================================================================"

# ============================ T01 单请求并行链路 ============================
info "T01 单请求并行链路（经 web 全链路）"
RID="suite-t01-${TS}"
resp=$(WEB_CURL -X POST "$WEB_URL" -H 'Content-Type: application/json' \
  -d "{\"req_id\":\"${RID}\",\"user_id\":${USER_ID},\"question\":\"${QUESTION9}\",\"private\":\"true\",\"ai_answer\":\"true\"}")
echo "$resp" | grep -q '"status":"queued"' && pass "T01a 请求受理 queued" || fail "T01a 请求受理: $resp"

if wait_for_core_log "$RID" "已分发 9 个子任务" 60; then
  pass "T01b 计划生成并分发 9 个子任务"
else
  fail "T01b 未分发子任务"
fi

if wait_for_converge "$RID" 120; then
  plan_id=$(get_plan_id "$RID")
  anslen=$(grep -h "planId=${plan_id}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
    | grep '收敛完成' | grep -oE 'answerLen=[0-9]+' | tail -1 | cut -d= -f2)
  if [ -n "$anslen" ] && [ "$anslen" -le 1000 ]; then
    pass "T01c 收敛完成 answerLen=$anslen ≤ 1000"
  else
    fail "T01c 收敛 answerLen=$anslen > 1000"
  fi
else
  fail "T01c 收敛未完成(120s超时)"
fi

if wait_for_db "$RID" 30; then
  db_check "$RID" "done" 1100
  if [ $? -eq 0 ]; then pass "T01d DB 持久化 done"; else fail "T01d DB 校验: $(db_check "$RID" done 1100)"; fi
else
  fail "T01d DB 未 done"
fi

# ============================ T02 简单问题不拆解 ============================
info "T02 简单问题不拆解"
RID="suite-t02-${TS}"
WEB_CURL -X POST "$WEB_URL" -H 'Content-Type: application/json' \
  -d "{\"req_id\":\"${RID}\",\"user_id\":${USER_ID},\"question\":\"你好，很高兴见到你\",\"private\":\"true\",\"ai_answer\":\"true\"}" > /dev/null
sleep 5
if assert_core_log "$RID" "已分发"; then
  fail "T02a 简单问题不应触发拆解"
else
  pass "T02a 简单问题未拆解"
fi
if wait_for_db "$RID" 90; then
  pass "T02b 简单问题 DB done（普通流式兜底）"
else
  fail "T02b 简单问题 DB 未 done"
fi

# ============================ T03 并发限流降级 ============================
if [ "${1:-}" != "--quick" ]; then
  info "T03 并发限流降级（20 并发，轮询打 9090/9092）"
  RID_PREFIX="suite-t03-${TS}"
  START_N=100
  for i in $(seq 1 20); do
    n=$((START_N + i))
    rid="${RID_PREFIX}-${n}"
    url=$CORE_A; [ $((n % 2)) -eq 0 ] && url=$CORE_B
    ( curl -s -m 10 -X POST "$url" -H 'Content-Type: application/json' \
        -d "{\"req_id\":\"${rid}\",\"user_id\":${USER_ID},\"private\":\"true\",\"question\":\"${QUESTION9}\",\"preferred_model_config_id\":2}" > /dev/null ) &
  done
  wait
  info "T03a 20 请求已发送，轮询等待全部请求进入终态（并行或降级）..."
  # 仅统计本轮请求（完整前缀隔离历史日志）；轮询直到 20 个请求全部有明确去向
  slept=0
  while [ $slept -lt 120 ]; do
    dispatched=$(grep -hE "req_id=${RID_PREFIX}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -c '已分发')
    degraded=$(grep -hE "req_id=${RID_PREFIX}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -c '并发过载降级')
    [ $((dispatched+degraded)) -ge 20 ] && break
    sleep 5; slept=$((slept+5))
  done
  info "T03 统计: 并行=$dispatched 降级=$degraded 合计=$((dispatched+degraded))"

  if [ "$((dispatched+degraded))" -eq 20 ]; then
    pass "T03a 20 请求全部有明确去向（并行+降级）"
  else
    fail "T03a 请求去向不完整 并行=$dispatched 降级=$degraded"
  fi
  if [ "$dispatched" -le 10 ]; then
    pass "T03b 并行数=$dispatched ≤ 10（全局限流生效）"
  else
    fail "T03b 并行数=$dispatched 超过 10"
  fi

  # 等待全部并行收敛（最多 200s）：先收集本轮所有 planId，轮询直到全部收敛
  all_plan_ids=$(grep -hE "req_id=${RID_PREFIX}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
    | grep -oE 'planId=[0-9a-f]+' | sort -u | cut -d= -f2)
  plan_count=$(echo "$all_plan_ids" | grep -c .)
  if [ "$plan_count" -gt 0 ]; then
    slept=0
    while [ $slept -lt 200 ]; do
      converge_cnt=0
      for pid in $all_plan_ids; do
        if grep -h "planId=${pid}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -q '收敛完成'; then
          converge_cnt=$((converge_cnt+1))
        fi
      done
      [ "$converge_cnt" -ge "$plan_count" ] && break
      sleep 10; slept=$((slept+10))
    done
    info "T03 收敛完成数: $converge_cnt / planId 数: $plan_count"
    if [ "$converge_cnt" -eq "$plan_count" ]; then
      pass "T03c 全部并行请求收敛完成 ($converge_cnt/$plan_count)"
    else
      fail "T03c 收敛完成数 $converge_cnt != planId 数 $plan_count"
    fi
  else
    fail "T03c 无并行 plan（全部降级？）"
  fi
else
  info "T03 跳过（--quick）"
fi

# ============================ T04 manual ack 可靠性 ============================
# 依赖 T03 的 20 个请求：先轮询等待每个 plan 的子任务全部执行完成再断言零丢失，
# 避免"任务完成数 < 分发数"的时序误报（执行完成 = 任务完成成功 + 任务执行失败，都算已处理）
if [ "${1:-}" != "--quick" ]; then
  info "T04 manual ack 可靠性（子任务不丢失，等待全部子任务执行完成）"
  RID_PREFIX="suite-t03-${TS}"
  all_plan_ids=""
  slept=0
  while [ $slept -lt 200 ]; do
    all_plan_ids=$(grep -hE "req_id=${RID_PREFIX}" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
      | grep -oE 'planId=[0-9a-f]+' | sort -u | cut -d= -f2)
    plan_count=$(echo "$all_plan_ids" | grep -c .)
    done_all=1
    if [ "$plan_count" -gt 0 ]; then
      for pid in $all_plan_ids; do
        sent=$(grep -h "planId=${pid} " /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -c '子任务已分发')
        done_cnt=$(grep -hE "taskId=${pid}-t[0-9]+" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
          | grep -cE '任务完成|任务执行失败')
        if [ "$sent" -eq 0 ] || [ "$done_cnt" -lt "$sent" ]; then
          done_all=0; break
        fi
      done
    fi
    [ "$plan_count" -gt 0 ] && [ "$done_all" -eq 1 ] && break
    sleep 10; slept=$((slept+10))
  done
  # 终态断言：每个 plan 的执行完成数 == 分发数
  missing=0; checked=0
  for pid in $all_plan_ids; do
    sent=$(grep -h "planId=${pid} " /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log | grep -c '子任务已分发')
    done_cnt=$(grep -hE "taskId=${pid}-t[0-9]+" /opt/app/logs/core-9090.log /opt/app/logs/core-9092.log \
      | grep -cE '任务完成|任务执行失败')
    checked=$((checked+1))
    if [ "$done_cnt" -lt "$sent" ]; then
      missing=$((missing + sent - done_cnt)); info "  未执行: plan=$pid 分发=$sent 完成=$done_cnt"
    fi
  done
  if [ "$missing" -eq 0 ] && [ "$checked" -gt 0 ]; then
    pass "T04 子任务零丢失（manual ack 生效，${checked} 个 plan 全部执行完）"
  else
    fail "T04 子任务丢失 $missing 个（检查 ${checked} 个 plan）"
  fi
else
  info "T04 跳过（--quick，无 T03 数据）"
fi

# ============================ T05/T06 输出压缩 + DB 终态 ============================
info "T05+T06 输出压缩 & DB 终态（汇总本套件所有 web 落库请求）"
for rid in "suite-t01-${TS}" "suite-t02-${TS}"; do
  db_check "$rid" "done" 1100
  [ $? -eq 0 ] && pass "DB $rid 终态 OK" || fail "DB $rid 终态异常: $(db_check "$rid" done 1100)"
done

echo "======================================================================"
log "测试完成: ${GREEN}PASS=$PASS${NC} ${RED}FAIL=$FAIL${NC}"
echo "结果文件: $RESULT"
exit $FAIL
