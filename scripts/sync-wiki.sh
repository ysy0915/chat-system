#!/bin/bash
# =============================================================================
# sync-wiki.sh — 从 docs/（唯一源）自动导出 wiki/（GitHub Wiki 发布目录）
#
# 背景：项目曾同时维护 docs/ 与 wiki/ 两份文档，靠人工同步，导致
#       版本标注、数字、端口等多次漂移（如 ADR 条数、web 单/双实例）。
#       本脚本以 docs/ 为唯一权威源，一键重建 wiki/，根治漂移。
#
# 用法：
#   bash scripts/sync-wiki.sh          # 同步（默认）
#   bash scripts/sync-wiki.sh check    # 只检查差异，不写文件（供 CI 用）
#   bash scripts/sync-wiki.sh force    # 覆盖 wiki 侧独有文件（慎用）
#
# 约定：
#   - docs/0X-分类/文档.md  →  wiki/文档.md（平铺，去分类目录）
#   - docs/README.md         →  wiki/README.md
#   - wiki/Home.md、_Sidebar.md、_Footer.md 为 wiki 特有文件，不在 docs 中维护
#   - docs/ 下的非 .md 资产（sql、yml、png 等）不进 wiki（wiki 是纯文档站）
# =============================================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS="$ROOT/docs"
WIKI="$ROOT/wiki"
MODE="${1:-sync}"   # sync | check | force

# 不导出到 wiki 的 docs 目录（部署资产/配置，非文档）
# 说明：SQL/配置/监控 yml 属于部署资产，GitHub Wiki 只承载 markdown 文档。
SKIP_DIRS=("db-migrations" "sql" "grafana" "nacos-configs")

log()  { echo "[sync-wiki] $*"; }
fail() { echo "[sync-wiki][错误] $*" >&2; exit 1; }

[[ -d "$DOCS" ]] || fail "docs/ 目录不存在：$DOCS"
mkdir -p "$WIKI"

# 收集需要同步的（相对 docs 的）md 文件 → 目标文件名
# 返回 "相对路径|目标名" 列表
collect() {
  local out=()
  # 1) docs 根 README.md → wiki/README.md
  [[ -f "$DOCS/README.md" ]] && out+=("README.md|README.md")
  # 2) docs 各分类子目录下的 .md → wiki 平铺
  local dir file
  while IFS= read -r -d '' dir; do
    local base
    base="$(basename "$dir")"
    # 跳过非文档资产目录
    local skip=""
    for s in "${SKIP_DIRS[@]}"; do
      [[ "$base" == "$s" ]] && skip=1 && break
    done
    [[ -n "$skip" ]] && continue
    while IFS= read -r -d '' file; do
      local rel target
      rel="${file#"$DOCS"/}"
      target="$(basename "$file")"
      out+=("$rel|$target")
    done < <(find "$dir" -maxdepth 1 -name '*.md' -print0)
  done < <(find "$DOCS" -mindepth 1 -maxdepth 1 -type d -print0)
  printf '%s\n' "${out[@]}"
}

# 文件名冲突检测（不同分类目录下同名 md 会互相覆盖）
conflicts="$(collect | awk -F'|' '{print $2}' | sort | uniq -d)"
[[ -z "$conflicts" ]] || fail "存在同名文档，平铺导出会互相覆盖：$conflicts"

changed=0
missing=0

while IFS='|' read -r rel target; do
  src="$DOCS/$rel"
  dst="$WIKI/$target"
  [[ -f "$src" ]] || continue

  if [[ ! -f "$dst" ]]; then
    if [[ "$MODE" == "check" ]]; then
      log "缺失（check）：$target"
      missing=$((missing+1))
    else
      cp "$src" "$dst"
      log "新增：$target"
      changed=$((changed+1))
    fi
  elif ! cmp -s "$src" "$dst"; then
    if [[ "$MODE" == "check" ]]; then
      log "差异（check）：$target"
      changed=$((changed+1))
    else
      cp "$src" "$dst"
      log "更新：$target"
      changed=$((changed+1))
    fi
  fi
done < <(collect)

# --force：删除 wiki 侧 docs 中已不存在的过期文档（默认不删，提示即可）
if [[ "$MODE" == "force" ]]; then
  local valid_names
  valid_names="$(collect | awk -F'|' '{print $2}' | sort -u)"
  while IFS= read -r -d '' f; do
    local name
    name="$(basename "$f")"
    # 保留 wiki 特有文件
    case "$name" in
      Home.md|_Sidebar.md|_Footer.md) continue ;;
    esac
    if ! grep -qxF "$name" <<<"$valid_names"; then
      rm -f "$f"
      log "删除过期：$name（docs 中已不存在）"
      changed=$((changed+1))
    fi
  done < <(find "$WIKI" -maxdepth 1 -name '*.md' -print0)
fi

if [[ "$MODE" == "check" ]]; then
  if [[ "$changed" -gt 0 || "$missing" -gt 0 ]]; then
    fail "docs/ 与 wiki/ 不一致：$changed 处差异、$missing 处缺失。请运行 bash scripts/sync-wiki.sh"
  fi
  log "docs/ 与 wiki/ 完全一致 ✓"
else
  log "完成：同步 $changed 处变更"
  log "提示：Home.md / _Sidebar.md / _Footer.md 为 wiki 特有文件，如需调整请直接编辑 wiki/ 下对应文件"
fi
