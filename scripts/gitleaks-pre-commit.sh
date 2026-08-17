#!/bin/bash
# ============================================================
# pre-commit 密钥泄露拦截钩子
# 在 git commit 前扫描暂存区，发现密钥/敏感信息则阻止提交。
#
# 用途：防止 API Key / 密码 / 私钥再次误提交进仓库（AK 事故闭环）。
# 与 .github/workflows/security.yml 的 gitleaks 形成双保险：
#   - 本钩子：提交前本地拦截（治本，key 进不了历史）
#   - CI gitleaks：提交后兜底（防止绕过钩子直接 push）
#
# 安装：bash scripts/install-git-hooks.sh
# ============================================================

set -uo pipefail

# ---------- 颜色 ----------
red()    { echo -e "\033[31m[BLOCK] $1\033[0m"; }
green()  { echo -e "\033[32m[ OK ] $1\033[0m"; }
yellow() { echo -e "\033[33m[WARN] $1\033[0m"; }

# ---------- 定位项目根目录 ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GITLEAKS_CONFIG="$PROJECT_ROOT/.gitleaks.toml"

# ---------- 1. 优先用 gitleaks 二进制（若已安装） ----------
if command -v gitleaks >/dev/null 2>&1; then
    # 只扫描暂存区（staged），不扫历史
    if gitleaks protect --staged --config "$GITLEAKS_CONFIG" -v 2>/dev/null; then
        green "gitleaks 扫描通过，无密钥泄露"
        exit 0
    else
        red "gitleaks 检测到疑似密钥泄露，已阻止提交！"
        red "请移除敏感信息后重新提交。若为误报，请更新 .gitleaks.toml 白名单。"
        exit 1
    fi
fi

# ---------- 2. 未安装 gitleaks 时，用内置正则降级扫描 ----------
yellow "未检测到 gitleaks 二进制，使用内置正则降级扫描（建议安装 gitleaks 获得完整规则）"

# 内置高危正则（与 .gitleaks.toml 的 generic-api-key / jwt-secret / private-key 对齐）
# 注意：使用 grep -E（BSD grep 兼容），大小写不敏感用 -i 参数（不用 (?i) 内联，macOS 不支持 -P）
# 为避免 shell 引号嵌套出错，正则不匹配引号本身，只匹配「key名 + 分隔符 + 长串」
PATTERNS=(
    # 通用 API Key / Token / Secret 赋值（值可带引号也可不带）
    '(api[_-]?key|api[_-]?secret|secret[_-]?key|access[_-]?token|auth[_-]?token|private[_-]?key)[[:space:]]*[:=][[:space:]]*.{0,2}[a-zA-Z0-9+/=_-]{20,}'
    # JWT 签名密钥
    '(jwt[_-]?secret|jwt[_-]?key|token[_-]?secret)[[:space:]]*[:=][[:space:]]*.{0,2}[a-zA-Z0-9+/=_-]{16,}'
    # PEM 私钥头
    '-----BEGIN[[:space:]](RSA[[:space:]]|EC[[:space:]]|OPENSSH[[:space:]]|DSA[[:space:]])?PRIVATE[[:space:]]KEY-----'
    # 数据库连接串含明文密码
    '(jdbc|mysql|postgresql|mongodb|redis)://[^@[:space:]]+:[^@[:space:]]+@'
)

# 扫描暂存区文件（含未跟踪文件），命中即阻断
BLOCKED=0
for file in $(git -C "$PROJECT_ROOT" diff --cached --name-only --diff-filter=ACM 2>/dev/null; git -C "$PROJECT_ROOT" ls-files --others --exclude-standard 2>/dev/null); do
    [ -f "$PROJECT_ROOT/$file" ] || continue
    case "$file" in
        docs/*.md|docs/nginx.conf|src/test/*|frontend/.env.development|target/*|node_modules/*|frontend/dist/*|.idea/*|.gitleaks.toml)
            continue ;;
    esac
    for pat in "${PATTERNS[@]}"; do
        if grep -Eiq "$pat" "$PROJECT_ROOT/$file" 2>/dev/null; then
            red "检测到疑似密钥：$file"
            grep -Ein "$pat" "$PROJECT_ROOT/$file" 2>/dev/null | head -3
            BLOCKED=1
        fi
    done
done

if [ "$BLOCKED" -eq 1 ]; then
    red "已阻止提交！请移除敏感信息（或改用环境变量）后重试。"
    exit 1
fi

green "内置正则扫描通过，无密钥泄露"
exit 0
