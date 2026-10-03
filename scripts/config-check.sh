#!/bin/bash
# ============================================================
# 配置一致性体检（config-doctor）—— 配置管理治理配套工具
#
# 背景：LLM 401 连环排障发现配置三源（.env / Nacos / DB）分裂无校验，
#       踩过的坑：QIANWEN_API_KEY 命名漂移（代码只读 QWEN_API_KEY）、
#       .env 重复变量（后值静默覆盖前值）、install-server.sh 内嵌模板
#       与 .env.template 双源漂移（缺 APP_MASTER_KEY → key 明文落库）。
#
# 检查项：
#   1) 重复变量   env 文件中同名变量定义多次（后值覆盖前值，排障大坑）→ ERROR
#   2) 死变量     定义了但代码/脚本无任何引用（多为命名漂移）→ KEY/TOKEN/PASSWORD 类为 ERROR
#   3) 模板漂移   install-server.sh 内嵌 .env 模板 vs .env.template 变量集差异 → ERROR
#   4) 缺配必填   代码中 ${VAR}（无默认值，必须显式提供）但两份模板均未提供 → WARN
#   5) 占位值     值仍为 changeme / your_* / REPLACE_* / 空（真实 env 才报）→ WARN
#
# 用法：
#   bash scripts/config-check.sh                     # 体检 .env（无则 .env.template）
#   bash scripts/config-check.sh <env文件路径>        # 体检指定文件
#   bash scripts/config-check.sh --remote root@host  # 远端体检 /opt/app/.env（凭据不落地本机）
#
# 退出码：存在 ERROR → 1（可作 deploy.sh 前置卡点）；仅 WARN/INFO → 0
# ============================================================
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATE="$ROOT/.env.template"
INSTALL_SH="$ROOT/scripts/install-server.sh"
ERRORS=0
WARNS=0

red()    { echo -e "\033[31m[ERROR] $1\033[0m"; }
yellow() { echo -e "\033[33m[WARN ] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK  ] $1\033[0m"; }
info()   { echo -e "\033[36m[INFO ] $1\033[0m"; }

err() { red "$1"; ERRORS=$((ERRORS+1)); }
warn(){ yellow "$1"; WARNS=$((WARNS+1)); }

# ---------- 0) 对象选取 ----------
if [ "${1:-}" = "--remote" ] && [ -n "${2:-}" ]; then
    ENV_FILE=$(mktemp /tmp/env-check.XXXXXX)
    trap 'rm -f "$ENV_FILE"' EXIT
    if ! ssh "${2}" 'cat /opt/app/.env' > "$ENV_FILE" 2>/dev/null; then
        red "无法读取远端 ${2}:/opt/app/.env"; exit 1
    fi
    LABEL="${2}:/opt/app/.env"; IS_TEMPLATE=0
else
    ENV_FILE="${1:-$ROOT/.env}"
    if [ ! -f "$ENV_FILE" ] && [ -f "$TEMPLATE" ]; then ENV_FILE="$TEMPLATE"; fi
    [ -f "$ENV_FILE" ] || { red "找不到可检查的 env 文件（.env / .env.template 均不存在）"; exit 1; }
    LABEL="$ENV_FILE"; IS_TEMPLATE=$([ "$ENV_FILE" = "$TEMPLATE" ] && echo 1 || echo 0)
fi

echo "============================================================"
info "配置体检: $LABEL"
echo "============================================================"

# ---------- 1) 重复变量 ----------
DUPS=$(grep -E '^[A-Z][A-Z0-9_]*=' "$ENV_FILE" | cut -d= -f1 | sort | uniq -d)
if [ -n "$DUPS" ]; then
    while IFS= read -r v; do
        err "重复定义: ${v}（.env 后值覆盖前值，仅最后一条生效——历史 401 排障直接踩坑）"
        grep -nE "^$v=" "$ENV_FILE" | sed 's/^/         /' | while read -r l; do yellow "  $l"; done
    done <<< "$DUPS"
else
    green "无重复变量定义"
fi

# ---------- 2) 引用集合 ----------
# 运行时引用（YAML/Java @Value/System.getenv + Nacos 副本）：环境变量的真正消费方
REFS=$( { grep -rhE --include='*.yml' --include='*.java' '\$\{' \
             "$ROOT"/chat-*/src "$ROOT"/docs/nacos-configs 2>/dev/null \
           | grep -vE '^[[:space:]]*(#|//|/\*|\*)' | grep -oE '\$\{[A-Z][A-Z0-9_]+[:}]' | sed -E 's/^\$\{//; s/[:}]$//'
           grep -rhoE 'System\.getenv\("[A-Z][A-Z0-9_]+"' "$ROOT"/chat-*/src --include='*.java' 2>/dev/null \
           | sed -E 's/.*"([^"]+)"/\1/'
         } | LC_ALL=C sort -u )
# 必须显式提供的引用（${VAR} 无默认值）
MUST=$(grep -rhE --include='*.yml' --include='*.java' '\$\{' \
           "$ROOT"/chat-*/src "$ROOT"/docs/nacos-configs 2>/dev/null \
         | grep -vE '^[[:space:]]*(#|//|/\*|\*)' | grep -oE '\$\{[A-Z][A-Z0-9_]+\}' | sed -E 's/[${}]//g' \
         | LC_ALL=C sort -u)
# 脚本/工具引用（运维脚本 export、告警 py）：仅用于死变量豁免，不用于缺配判定
EXTRA=$( { grep -rhoE '\$\{[A-Z][A-Z0-9_]+\}' "$ROOT"/scripts "$ROOT"/docs --include='*.sh' --include='*.py' 2>/dev/null \
             | sed -E 's/[${}]//g'
           grep -rhoE 'environ\.get\(\s*"[A-Z][A-Z0-9_]+"' "$ROOT"/docs "$ROOT"/scripts --include='*.py' 2>/dev/null \
             | sed -E 's/.*"([^"]+)"/\1/'
         } | LC_ALL=C sort -u )
info "运行时环境变量引用共 $(echo "$REFS" | grep -c .) 个（其中必须显式提供 $(echo "$MUST" | grep -c .) 个）"

# ---------- 3) 死变量 ----------
# 预留变量：当前无代码引用但为未来预留（Nacos 开鉴权后使用），降级为提示
RESERVED="^(NACOS_USERNAME|NACOS_PASSWORD)$"
DEAD_LIST=""
while IFS= read -r v; do
    { echo "$REFS"; echo "$EXTRA"; } | grep -qx "$v" || DEAD_LIST="$DEAD_LIST$v
"
done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$ENV_FILE" | cut -d= -f1 | sort -u)

if [ -n "$DEAD_LIST" ]; then
    while IFS= read -r v; do
        [ -z "$v" ] && continue
        echo "$v" | grep -Eq "$RESERVED" && { info "预留变量: ${v}（当前无代码引用，为 Nacos 开启鉴权等场景预留）"; continue; }
        case "$v" in
            *KEY*|*TOKEN*|*PASSWORD*)
                err "疑似命名漂移的死变量: ${v}（定义了但代码不读取——LLM Key 类多为拼写错误，兜底永远不生效）" ;;
            *)
                warn "死变量: ${v}（定义了但代码/脚本无引用，请确认是否可删）" ;;
        esac
    done <<< "$DEAD_LIST"
else
    green "无死变量（所有定义均被代码/脚本引用）"
fi

# ---------- 4) 模板漂移（.env.template vs install-server.sh 内嵌模板） ----------
INSTALL_KEYS=""
if [ -f "$TEMPLATE" ] && [ -f "$INSTALL_SH" ]; then
    INSTALL_KEYS=$(awk '/cat > "\$ENV_FILE" <<EOF/{f=1;next} /^EOF$/{f=0} f && /^[A-Z][A-Z0-9_]*=/' "$INSTALL_SH" | cut -d= -f1 | sort -u)
    TPL_KEYS=$(grep -E '^[A-Z][A-Z0-9_]*=' "$TEMPLATE" | cut -d= -f1 | sort -u)
    DRIFT=0
    while IFS= read -r v; do
        echo "$INSTALL_KEYS" | grep -qx "$v" || { err "模板漂移: $v 仅在 .env.template，install-server.sh 生成的服务器 .env 缺失（新装机器将缺配）"; DRIFT=1; }
    done <<< "$TPL_KEYS"
    while IFS= read -r v; do
        echo "$TPL_KEYS" | grep -qx "$v" || { err "模板漂移: $v 仅在 install-server.sh 模板，.env.template 缺失（仓库文档口径不全）"; DRIFT=1; }
    done <<< "$INSTALL_KEYS"
    [ "$DRIFT" = "0" ] && green "两份模板变量集一致（.env.template ↔ install-server.sh）"
fi

# ---------- 5) 缺配必填（无默认值引用，模板都没提供） ----------
if [ -f "$TEMPLATE" ] && [ -n "$INSTALL_KEYS" ]; then
    PROVIDED=$( { grep -E '^[A-Z][A-Z0-9_]*=' "$TEMPLATE" | cut -d= -f1; echo "$INSTALL_KEYS"; } | LC_ALL=C sort -u )
    # 运行时注入/部署期变量，不属于 .env 职责
    RUNTIME_WHITELIST="^(SPRING_PROFILES_ACTIVE|APP_NAME|GRPC_PORT|SERVER_PORT|LOG_PATH|JAVA_OPTS|PATH|HOME|USER)$"
    MISSING=$(comm -23 <(echo "$MUST") <(echo "$PROVIDED") | grep -Ev "$RUNTIME_WHITELIST" || true)
    if [ -n "$MISSING" ]; then
        warn "以下变量被代码以无默认值方式引用（\${VAR}），但两份模板均未提供——不配则启动即解析失败:"
        echo "$MISSING" | sed 's/^/         /' | while read -r l; do yellow "  $l"; done
    else
        green "代码必填引用在模板中均有提供"
    fi
fi

# ---------- 6) 占位值（仅对真实 env 报，模板占位是预期） ----------
if [ "$IS_TEMPLATE" = "0" ]; then
    PLACEHOLDER=0
    while IFS='=' read -r k v; do
        case "$v" in
            changeme*|your_*|REPLACE_*|"")
                case "$k" in
                    *KEY*|*PASSWORD*|*TOKEN*|*SECRET*)
                        warn "占位值未填: ${k}（仍为 '${v}'——密钥类为空将导致对应功能 401/裸奔）"; PLACEHOLDER=1 ;;
                esac ;;
        esac
    done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$ENV_FILE")
    [ "$PLACEHOLDER" = "0" ] && green "密钥类变量均已填写"
fi

# ---------- 汇总 ----------
echo "============================================================"
if [ "$ERRORS" -gt 0 ]; then
    red "体检结果: ${ERRORS} 个 ERROR, ${WARNS} 个 WARN —— 存在配置漂移，请修复后再部署"
    exit 1
else
    green "体检结果: 0 ERROR, ${WARNS} 个 WARN"
    exit 0
fi
