-- =====================================================================
-- V1.2.0: messages.req_id 唯一索引 + question ngram 全文索引（幂等，可重复执行）
--
-- 背景：
--   1. messages.req_id 用于幂等去重（防止 AI 回答重复落库/重复推送）。
--      线上已存在唯一索引 uk_req_id（2026-08-15 核实），本脚本幂等跳过，
--      不再重复创建。
--   2. 私聊/树洞搜索目前走 LIKE '%keyword%' 全表扫描；本脚本提供 ngram
--      全文索引，供低峰期切换 MATCH...AGAINST 查询。
--
-- 注意：
--   - 全文索引为可选优化，需 MySQL 8.0+ 且 ngram 解析器按 2 字符切词，
--     1 个字的关键词无法命中；切换查询语法需同步修改 Repository。
--   - 建议低峰期执行（大表建索引会锁写）。
--   - 列名为 question（text 类型），非 content。
-- =====================================================================

-- 1. messages.req_id 唯一索引（幂等）：检测任意 req_id 唯一索引，已存在则跳过
SET @uq_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND column_name = 'req_id'
      AND non_unique = 0
);
SET @ddl := IF(@uq_exists = 0,
    'ALTER TABLE messages ADD UNIQUE INDEX uq_messages_reqid (req_id)',
    'SELECT ''[SKIP] messages.req_id 唯一索引已存在'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. messages.question ngram 全文索引（幂等，可选）
SET @ft_exists := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'messages'
      AND index_name = 'idx_ft_question_ngram'
);
SET @ddl2 := IF(@ft_exists = 0,
    'ALTER TABLE messages ADD FULLTEXT INDEX idx_ft_question_ngram (question) WITH PARSER ngram',
    'SELECT ''[SKIP] messages.idx_ft_question_ngram already exists'' AS msg');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- 3. tree_hole_messages.question ngram 全文索引（幂等，可选）
SET @ft_exists2 := (
    SELECT COUNT(1)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'tree_hole_messages'
      AND index_name = 'idx_ft_question_ngram'
);
SET @ddl3 := IF(@ft_exists2 = 0,
    'ALTER TABLE tree_hole_messages ADD FULLTEXT INDEX idx_ft_question_ngram (question) WITH PARSER ngram',
    'SELECT ''[SKIP] tree_hole idx_ft_question_ngram already exists'' AS msg');
PREPARE stmt3 FROM @ddl3;
EXECUTE stmt3;
DEALLOCATE PREPARE stmt3;

-- 验证：SHOW INDEX FROM messages; SHOW INDEX FROM tree_hole_messages;
