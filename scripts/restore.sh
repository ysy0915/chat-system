#!/bin/bash
# ============================================================
# 服务器端恢复脚本（backup.sh 的逆操作）—— 事故时使用，先读后执行
#
# 用法（服务器上执行，root）：
#   /opt/app/restore.sh list                       # 列出可用备份
#   /opt/app/restore.sh mysql  <db>-xxxx.sql.gz     # 恢复 MySQL（整库覆盖）
#   /opt/app/restore.sh milvus milvus-xxxx.tar.gz  # 恢复 Milvus（清空现数据后解压）
#   追加 --yes 跳过交互确认
#
# ⚠️ 恢复是破坏性操作：
#   mysql  ：目标库整库覆盖（DROP 重建语义，以 dump 内容为准）
#   milvus ：清空 /opt/app/milvus/volumes 后解压（现有图谱/向量库全部丢弃）
# ============================================================
set -uo pipefail
ENV_FILE="/opt/app/.env"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

BACKUP_DIR="${BACKUP_DIR:-/opt/app/backups}"
MILVUS_VOLUMES="/opt/app/milvus/volumes"
ASSUME_YES=0
for a in "$@"; do [ "$a" = "--yes" ] && ASSUME_YES=1; done

die() { echo "[FAIL] $1"; exit 1; }

resolve() {  # 相对路径→BACKUP_DIR 下查找
    [ -f "$1" ] && { echo "$1"; return; }
    [ -f "$BACKUP_DIR/$1" ] && { echo "$BACKUP_DIR/$1"; return; }
    echo "$1"
}

confirm() {
    [ "$ASSUME_YES" = "1" ] && return 0
    read -p "$1 [输入 yes 确认]: " r
    [ "$r" = "yes" ] || die "已取消"
}

cmd="${1:-}"
file="${2:-}"

case "$cmd" in
list)
    echo "==== MySQL 备份（$BACKUP_DIR/mysql）===="
    ls -lh "$BACKUP_DIR/mysql"/*.sql.gz 2>/dev/null || echo "（无）"
    echo "==== Milvus 备份（$BACKUP_DIR/milvus）===="
    ls -lh "$BACKUP_DIR/milvus"/*.tar.gz 2>/dev/null || echo "（无）"
    ;;

mysql)
    [ -n "$file" ] || die "用法: restore.sh mysql <备份文件.sql.gz>"
    f=$(resolve "$file"); [ -f "$f" ] || die "找不到备份文件: $file"
    command -v mysql >/dev/null 2>&1 || die "mysql 客户端未安装"
    [ -n "${DB_URL:-}" ] || die "DB_URL 未配置"

    hostport=$(echo "$DB_URL" | sed -E 's|^jdbc:mysql://([^/?]+).*|\1|')
    host=${hostport%%:*}; port=${hostport##*:}; [ "$port" = "$host" ] && port=3306
    db=$(echo "$DB_URL" | sed -E 's|^jdbc:mysql://[^/]+/([^?]+).*|\1|')

    echo "目标: $db @ $host:$port"
    echo "备份: $f ($(du -h "$f" | cut -f1))"
    echo "⚠️  dump 内含 DROP/CREATE 语句，目标库同库数据将被覆盖"
    confirm "确认恢复到 $db ？"
    gunzip -c "$f" | MYSQL_PWD="${DB_PASSWORD:-}" mysql -h "$host" -P "$port" -u "${DB_USERNAME:-root}" "$db" \
        && echo "[ OK ] MySQL 恢复完成" || die "恢复执行失败（查看上方错误）"
    ;;

milvus)
    [ -n "$file" ] || die "用法: restore.sh milvus <备份文件.tar.gz>"
    f=$(resolve "$file"); [ -f "$f" ] || die "找不到备份文件: $file"
    gzip -t "$f" 2>/dev/null || die "备份文件 gzip 校验失败，文件损坏"

    echo "备份: $f ($(du -h "$f" | cut -f1))"
    echo "⚠️  将停止 Milvus 三容器并清空 $MILVUS_VOLUMES，现有图谱/RAG 向量库全部丢弃"
    confirm "确认清空并恢复？"
    docker stop milvus-standalone milvus-minio milvus-etcd >/dev/null 2>&1 || true
    rm -rf "$MILVUS_VOLUMES"
    mkdir -p "$(dirname "$MILVUS_VOLUMES")"
    tar -xzf "$f" -C "$(dirname "$MILVUS_VOLUMES")" || { die "解压失败 —— 原数据已清空，务必重试其他备份"; }
    docker start milvus-etcd milvus-minio milvus-standalone >/dev/null 2>&1
    echo "[INFO] 等待 Milvus 健康..."
    ok=0
    for i in $(seq 1 40); do
        curl -sf -m 2 http://localhost:9091/healthz >/dev/null 2>&1 && { ok=1; break; }; sleep 3
    done
    [ "$ok" = "1" ] && echo "[ OK ] Milvus 恢复完成（healthz 通过）" \
        || die "Milvus 未在 120s 内恢复健康 —— 检查 docker logs milvus-standalone"
    ;;

*)
    echo "用法: restore.sh {list|mysql <file>|milvus <file>} [--yes]"
    echo "示例: restore.sh mysql test_data-20261003-040000.sql.gz"
    echo "      restore.sh milvus milvus-20261003-040000.tar.gz --yes"
    ;;
esac
