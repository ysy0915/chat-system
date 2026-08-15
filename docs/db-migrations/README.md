# SQL变更版本管理

此目录用于管理数据库Schema变更，类似Flyway/Liquibase的本地化方案。

## 命名规则

```
V{版本号}__{描述}.sql
```

例：
- `V1.0.0__init_schema.sql` — 初始化表结构
- `V1.1.0__add_messages_index.sql` — 添加messages表索引
- `V1.2.0__add_reqid_unique_and_fulltext_indexes.sql` — messages.req_id 唯一索引 + content ngram 全文索引（2026-08-15，需低峰期人工执行）

## 执行顺序

按版本号升序执行。每个脚本只执行一次，执行记录保存在 `schema_history` 表中。

## 使用方式

```bash
# 执行所有未应用的迁移
bash scripts/db-migrate.sh

# 查看迁移状态
bash scripts/db-migrate.sh status
```

## 变更记录

| 版本 | 描述 | 日期 |
|------|------|------|
| V1.0.0 | 初始化schema | 2024-01-01 |
| V1.1.0 | messages表联合索引 | 2026-08-09 |
| V1.2.0 | messages.req_id 唯一索引（幂等）+ content ngram 全文索引 | 2026-08-15 |
