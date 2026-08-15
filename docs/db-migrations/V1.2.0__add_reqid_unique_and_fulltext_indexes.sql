-- =====================================================================
-- V1.2.0: messages.req_id 唯一索引 + ngram 全文索引（幂等，可重复执行）
--
-- 背景：
--   1. messages.req_id 用于幂等去重（防止 AI 回答重复落库/重复推送），
--      但线上表缺唯一索引，高并发重复请求可能插入重复记录。
--   2. 私聊/树洞搜索目前走 LIKE '%keyword%' 全表扫描；本脚本提供 ngram
--      全文索引，供低峰期切换 MATCH...AGAINST 查询。
--
-- 注意：
--   - 执行前先确认无重复 req_id：
--       SELECT req_id, COUNT(*) c FROM messages GROUP BY req_id HAVING c > 1;
--     如有重复需先手工合并/删除，否则唯一索引创建失败。
--   - 全文索引为可选优化，需 MySQL 8.0+ 且 ngram 解析器按 2 字符切词，
--     1 个字的关键词无法命中；切换查询语法需同步修改 Repository。
--   - 建议低峰期执行（大表建索引会锁写）。
-- =====================================================================

-- 1. messages.req_id 唯一索引（幂等）
SET @idx_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND index_name = 'uq_messages_reqid'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE messages ADD UNIQUE INDEX uq_messages_reqid (req_id)',
    'SELECT ''[SKIP] uq_messages_reqid already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. messages.content ngram 全文索引（幂等，可选；content 列名按实际表结构调整）
SET @ft_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND index_name = 'idx_ft_content_ngram'
);
SET @ddl2 := IF(@ft_exists = 0,
    'ALTER TABLE messages ADD FULLTEXT INDEX idx_ft_content_ngram (content) WITH PARSER ngram',
    'SELECT ''[SKIP] idx_ft_content_ngram already exists'' AS msg');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 3. tree_hole_messages.content ngram 全文索引（幂等，可选）
SET @ft_exists2 := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tree_hole_messages'
      AND index_name = 'idx_ft_content_ngram'
);
SET @ddl3 := IF(@ft_exists2 = 0,
    'ALTER TABLE tree_hole_messages ADD FULLTEXT INDEX idx_ft_content_ngram (content) WITH PARSER ngram',
    'SELECT ''[SKIP] tree_hole idx_ft_content_ngram already exists'' AS msg');
PREPARE stmt3 FROM @ddl3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

-- 验证：SHOW INDEX FROM messages; SHOW INDEX FROM tree_hole_messages;
