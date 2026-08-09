#!/bin/bash
# ============================================================
# 数据库迁移脚本（本地化Harness Database DevOps等效方案）
# 用法：bash db-migrate.sh [status|apply]
# ============================================================

PROJECT_ROOT="/Users/apple/IdeaProjects/chat-system-project"
MIGRATION_DIR="$PROJECT_ROOT/docs/db-migrations"
MILVUS_PEM="/Users/apple/Downloads/Milvus.pem"
MILVUS_SERVER="root@121.40.188.98"

# RDS连接信息（从配置文件读取）
DB_HOST="rm-bp19c29bo9s7kfyb2.mysql.rds.aliyuncs.com"
DB_USER="yangsy"
DB_PASS="YangSy@0915!"
DB_NAME="test_data"

CMD=${1:-status}

echo "============================================================"
echo "  数据库迁移  cmd=$CMD  $(date)
============================================================"

# 确保schema_history表存在
ensure_history_table() {
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME -e \"
        CREATE TABLE IF NOT EXISTS schema_history (
            id INT AUTO_INCREMENT PRIMARY KEY,
            version VARCHAR(50) NOT NULL UNIQUE,
            description VARCHAR(200),
            script VARCHAR(200),
            executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            success TINYINT(1) DEFAULT 1
        );
    \" 2>/dev/null"
}

# 查看状态
show_status() {
    ensure_history_table
    echo ""
    echo "已执行的迁移:"
    ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME -e \"
        SELECT version, description, executed_at FROM schema_history ORDER BY version;
    \" 2>/dev/null"

    echo ""
    echo "待执行的迁移:"
    for sql in "$MIGRATION_DIR"/V*.sql; do
        [ -f "$sql" ] || continue
        VERSION=$(basename "$sql" | sed 's/__.*//')
        EXECUTED=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME -sN -e \"
            SELECT COUNT(*) FROM schema_history WHERE version='$VERSION';
        \" 2>/dev/null")
        if [ "$EXECUTED" = "0" ]; then
            echo "  [待执行] $VERSION $(basename "$sql")"
        fi
    done
}

# 执行迁移
apply_migrations() {
    ensure_history_table
    for sql in "$MIGRATION_DIR"/V*.sql; do
        [ -f "$sql" ] || continue
        VERSION=$(basename "$sql" | sed 's/__.*//')
        DESC=$(basename "$sql" | sed 's/V[0-9.]*__//; s/.sql//')

        # 检查是否已执行
        EXECUTED=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME -sN -e \"
            SELECT COUNT(*) FROM schema_history WHERE version='$VERSION';
        \" 2>/dev/null")
        if [ "$EXECUTED" != "0" ]; then
            echo "[跳过] $VERSION 已执行"
            continue
        fi

        echo "[执行] $VERSION: $DESC"
        # 上传SQL并执行
        scp -i "$MILVUS_PEM" "$sql" $MILVUS_SERVER:/tmp/migration.sql
        RESULT=$(ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME < /tmp/migration.sql 2>&1")
        if [ -z "$RESULT" ]; then
            ssh -i "$MILVUS_PEM" $MILVUS_SERVER "mysql -h $DB_HOST -u $DB_USER -p'$DB_PASS' $DB_NAME -e \"
                INSERT INTO schema_history (version, description, script) VALUES ('$VERSION', '$DESC', '$(basename "$sql")');
            \" 2>/dev/null"
            echo "  [成功] $VERSION 已应用"
        else
            echo "  [失败] $RESULT"
            break
        fi
    done
}

case $CMD in
    status) show_status ;;
    apply)  apply_migrations ;;
    *)      echo "用法: bash db-migrate.sh [status|apply]"; exit 1 ;;
esac
