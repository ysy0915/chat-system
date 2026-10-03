#!/bin/bash
# ============================================================
# 服务器端备份脚本（MySQL + Milvus + 异机复制）—— 数据层 P0 修复
#
# 背景：架构自评「数据层 6.5」最大短板——MySQL(RDS)/Milvus/Redis 全部零备份，
#       一次磁盘故障 = 不可逆数据丢失（Milvus 含知识图谱 + RAG 向量库）。
#
# 覆盖：
#   MySQL  ：mysqldump --single-transaction 逻辑全量（RDS 上若有云快照，本脚本为兜底）
#   Milvus ：停机快照（docker stop etcd/minio/standalone → tar → 重启 → healthz 等待）
#            默认凌晨低峰执行，停机窗口约 1~3 分钟（chat-llm 的 RAG/图谱期间不可用）
#   Redis  ：缓存/会话性质，不备份（重启即重建）
#
# 用法（服务器上执行，root）：
#   /opt/app/backup.sh                 # 全量备份（MySQL + Milvus 停机快照）
#   /opt/app/backup.sh --online        # Milvus 不停机（有一致性风险，自担）
#   /opt/app/backup.sh --mysql-only    # 仅 MySQL（Milvus 换代/维护期可单独跳过）
#   /opt/app/backup.sh --install-cron  # 自装每日 04:00 cron
#
# .env 可配（均有默认值）：
#   BACKUP_DIR=/opt/app/backups            本地备份目录
#   BACKUP_RETENTION_DAYS=7                本地保留天数
#   BACKUP_REMOTE_HOST=                    异机目标（如 root@112.124.106.108，空=仅本地）
#   BACKUP_REMOTE_DIR=/opt/app/backups     异机目录（两台服务器互为异机副本）
# ============================================================
set -uo pipefail
ENV_FILE="/opt/app/.env"
[ -f "$ENV_FILE" ] && set -a && . "$ENV_FILE" && set +a

BACKUP_DIR="${BACKUP_DIR:-/opt/app/backups}"
RETENTION="${BACKUP_RETENTION_DAYS:-7}"
REMOTE_HOST="${BACKUP_REMOTE_HOST:-}"
REMOTE_DIR="${BACKUP_REMOTE_DIR:-/opt/app/backups}"
MILVUS_VOLUMES="/opt/app/milvus/volumes"
LOG_FILE="/opt/app/logs/backup.log"
STAMP=$(date +%Y%m%d-%H%M%S)
FAIL=0
ONLINE=""
MYSQL_ONLY=0

log()  { echo "[$(date '+%F %T')] $1" | tee -a "$LOG_FILE"; }
ok()   { log "[ OK ] $1"; }
fail() { log "[FAIL] $1"; FAIL=1; }

install_cron() {
    local line="0 4 * * * /opt/app/backup.sh >> /opt/app/logs/backup-cron.log 2>&1"
    if crontab -l 2>/dev/null | grep -qF "/opt/app/backup.sh"; then
        echo "[SKIP] cron 已存在"; return 0
    fi
    (crontab -l 2>/dev/null; echo "$line") | crontab - && echo "[ OK ] 已安装每日 04:00 备份 cron" || { echo "[FAIL] cron 安装失败"; return 1; }
}

mkdir -p "$BACKUP_DIR/mysql" "$BACKUP_DIR/milvus" /opt/app/logs

# ---------------- 1) MySQL（RDS 逻辑全量） ----------------
backup_mysql() {
    command -v mysqldump >/dev/null 2>&1 || {
        fail "mysqldump 未安装（yum install mariadb-server 或 apt install mariadb-client）"; return 1; }
    [ -n "${DB_URL:-}" ] || { fail "DB_URL 未配置"; return 1; }

    local hostport host port db
    hostport=$(echo "$DB_URL" | sed -E 's|^jdbc:mysql://([^/?]+).*|\1|')
    host=${hostport%%:*}; port=${hostport##*:}; [ "$port" = "$host" ] && port=3306
    db=$(echo "$DB_URL" | sed -E 's|^jdbc:mysql://[^/]+/([^?]+).*|\1|')
    [ -n "$db" ] || { fail "DB_URL 无法解析库名"; return 1; }

    local out="$BACKUP_DIR/mysql/${db}-${STAMP}.sql.gz"
    log "---- MySQL 备份开始: $db @ $host:$port"
    if MYSQL_PWD="${DB_PASSWORD:-}" mysqldump --single-transaction --routines --triggers \
            --set-gtid-purged=OFF -h "$host" -P "$port" -u "${DB_USERNAME:-root}" "$db" \
            | gzip > "$out"; then
        gzip -t "$out" 2>/dev/null || { fail "MySQL 备份 gzip 校验失败: $out"; return 1; }
        local size; size=$(du -h "$out" | cut -f1)
        [ "$(du -k "$out" | cut -f1)" -lt 1 ] && { fail "MySQL 备份疑似空文件: $out"; return 1; }
        ok "MySQL 备份完成: $out ($size)"
    else
        fail "mysqldump 执行失败（检查 DB_URL/DB_USERNAME/DB_PASSWORD 与 RDS 白名单）"; rm -f "$out"; return 1
    fi
}

# ---------------- 2) Milvus（停机快照） ----------------
wait_milvus() {
    for i in $(seq 1 40); do
        curl -sf -m 2 http://localhost:9091/healthz >/dev/null 2>&1 && return 0
        sleep 3
    done
    return 1
}

backup_milvus() {
    docker ps --format '{{.Names}}' | grep -q milvus-standalone || {
        log "[SKIP] Milvus 容器未运行，跳过"; return 0; }
    [ -d "$MILVUS_VOLUMES" ] || { fail "Milvus 数据目录不存在: $MILVUS_VOLUMES"; return 1; }

    local out="$BACKUP_DIR/milvus/milvus-${STAMP}.tar.gz"
    log "---- Milvus 备份开始${ONLINE:+（online 模式，不停机）}"

    if [ "$ONLINE" != "1" ]; then
        log "停机窗口：停止 etcd/minio/standalone..."
        docker stop milvus-standalone milvus-minio milvus-etcd >/dev/null 2>&1 \
            || { fail "容器停止失败，中止 Milvus 备份"; return 1; }
    fi

    if tar -czf "$out" -C "$(dirname "$MILVUS_VOLUMES")" "$(basename "$MILVUS_VOLUMES")"; then
        gzip -t "$out" 2>/dev/null || fail "Milvus 备份 gzip 校验失败: $out"
        ok "Milvus 备份完成: $out ($(du -h "$out" | cut -f1))"
    else
        fail "Milvus tar 失败"
    fi

    if [ "$ONLINE" != "1" ]; then
        log "重启 Milvus 三容器..."
        docker start milvus-etcd milvus-minio milvus-standalone >/dev/null 2>&1
        if wait_milvus; then ok "Milvus 已恢复健康 (healthz)"
        else fail "Milvus 重启后 120s 内未通过 healthz —— 请人工检查！"; fi
    fi
}

# ---------------- 3) 过期清理 ----------------
cleanup() {
    log "---- 清理 ${RETENTION} 天前的本地备份"
    find "$BACKUP_DIR/mysql"  -name '*.sql.gz' -mtime +"$RETENTION" -delete 2>/dev/null
    find "$BACKUP_DIR/milvus" -name '*.tar.gz' -mtime +"$RETENTION" -delete 2>/dev/null
    ok "过期清理完成"
}

# ---------------- 4) 异机复制 ----------------
sync_remote() {
    [ -n "$REMOTE_HOST" ] || { log "[WARN] 未配置 BACKUP_REMOTE_HOST —— 仅本地副本（单机故障仍有全失风险）"; return 0; }
    log "---- 异机复制到 $REMOTE_HOST:$REMOTE_DIR"
    if command -v rsync >/dev/null 2>&1; then
        rsync -az --quiet "$BACKUP_DIR/" "$REMOTE_HOST:$REMOTE_DIR/" \
            && ok "异机复制完成 (rsync)" || fail "rsync 失败（检查两台服务器 ssh 互信）"
    else
        ssh "$REMOTE_HOST" "mkdir -p $REMOTE_DIR/mysql $REMOTE_DIR/milvus" 2>/dev/null
        scp -q "$BACKUP_DIR/mysql/"*.sql.gz "$REMOTE_HOST:$REMOTE_DIR/mysql/" 2>/dev/null \
            && ok "MySQL 异机复制完成" || fail "MySQL scp 失败"
        scp -q "$BACKUP_DIR/milvus/"*.tar.gz "$REMOTE_HOST:$REMOTE_DIR/milvus/" 2>/dev/null \
            && ok "Milvus 异机复制完成" || fail "Milvus scp 失败"
    fi
}

# ---------------- 主流程 ----------------
for arg in "$@"; do
    case "$arg" in
        --online)       ONLINE=1 ;;
        --mysql-only)   MYSQL_ONLY=1 ;;
        --install-cron) install_cron; exit $? ;;
    esac
done

log "================ 备份开始 $STAMP ================"
backup_mysql
[ "$MYSQL_ONLY" = "1" ] || backup_milvus
cleanup
sync_remote
log "================ 备份结束（FAIL=$FAIL） ================"
[ "$FAIL" = "0" ] && echo "备份全部成功" || echo "存在失败项，详见 $LOG_FILE"
exit "$FAIL"
