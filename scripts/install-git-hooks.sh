#!/bin/bash
# ============================================================
# 安装 Git 钩子：pre-commit 密钥拦截
#
# 用法：bash scripts/install-git-hooks.sh
# 效果：每次 git commit 前自动扫描暂存区，拦截密钥/敏感信息
# ============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOKS_DIR="$PROJECT_ROOT/.git/hooks"
PRE_COMMIT="$HOOKS_DIR/pre-commit"

# 校验在 git 仓库内
if [ ! -d "$PROJECT_ROOT/.git" ]; then
    echo "[ERROR] 当前目录不是 git 仓库：$PROJECT_ROOT"
    exit 1
fi

# 写入 pre-commit 钩子（调用核心扫描脚本）
cat > "$PRE_COMMIT" << 'EOF'
#!/bin/bash
# 自动生成的 pre-commit 钩子：调用密钥扫描脚本
# 源：scripts/install-git-hooks.sh
exec bash "$(git rev-parse --show-toplevel)/scripts/gitleaks-pre-commit.sh"
EOF

chmod +x "$PRE_COMMIT"
chmod +x "$SCRIPT_DIR/gitleaks-pre-commit.sh"

echo "============================================================"
echo "  Git pre-commit 钩子安装完成 ✅"
echo "  钩子文件: .git/hooks/pre-commit"
echo "  扫描脚本: scripts/gitleaks-pre-commit.sh"
echo ""
echo "  说明："
echo "  - 每次 git commit 前自动扫描暂存区，拦截密钥/敏感信息"
echo "  - 优先使用 gitleaks（若已安装），否则降级为内置正则"
echo "  - 安装 gitleaks: brew install gitleaks (macOS)"
echo ""
echo "  卸载：删除 .git/hooks/pre-commit 即可"
echo "============================================================"
