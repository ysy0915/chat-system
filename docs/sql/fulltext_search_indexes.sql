-- =====================================================================
-- 全文检索索引迁移脚本（性能优化）
-- ---------------------------------------------------------------------
-- 背景：
--   消息历史 / 个人对话 / 树洞 的关键词搜索当前使用
--   `LIKE '%keyword%'`，前置通配符导致无法命中普通 B-Tree 索引，
--   在数据量大（>100万行）时会产生全表扫描。
--
-- 方案：
--   对 question / answer_json / summary 列建立 FULLTEXT 索引。
--   MySQL 5.7+ 需开启 ngram 分词（支持中文），8.0 默认支持。
--
-- 注意：
--   1. 建索引期间会锁表，请安排在低峰期执行。
--   2. 本脚本是【可选】优化，不执行不影响现有功能。
--   3. 若需真正切换到 MATCH...AGAINST 查询，需同步修改
--      MessageRepository.searchQuestions / searchPrivateMessages /
--      countSearchPrivateMessages / searchTreeHoleMessages 的 SQL。
-- =====================================================================

-- 1. 启用 ngram 全文索引插件（MyISAM/InnoDB 均可）
--    需在 my.cnf 中配置：
--      [mysqld]
--      ngram_token_size=2
--   或运行时执行（重启失效）：
--      SET GLOBAL innodb_ft_min_token_size = 2;
--      SET GLOBAL innodb_ft_server_stopword_table = '';

-- 2. 为消息表添加全文索引（公开搜索 + 个人对话搜索共用 messages 表）
--    普通索引无法加速 LIKE '%xx%'，FULLTEXT 索引用于 MATCH...AGAINST
ALTER TABLE messages
    ADD FULLTEXT INDEX ft_messages_question (question)
        WITH PARSER ngram,
    ADD FULLTEXT INDEX ft_messages_answer (answer_json)
        WITH PARSER ngram;

-- 3. 为树洞消息表添加全文索引
ALTER TABLE tree_hole_messages
    ADD FULLTEXT INDEX ft_treehole_question (question)
        WITH PARSER ngram;

-- 4. （可选）替换 LIKE 为 MATCH 的示例查询（需同步修改 Repository）：
--    SELECT id, question FROM messages
--    WHERE (is_private IS NULL OR is_private = 0)
--      AND answer_json IS NOT NULL AND answer_json != ''
--      AND MATCH(question) AGAINST (#{keyword} IN NATURAL LANGUAGE MODE)
--    ORDER BY created_at DESC LIMIT 30;

-- 5. 回滚（如需撤销）：
--    ALTER TABLE messages DROP INDEX ft_messages_question;
--    ALTER TABLE messages DROP INDEX ft_messages_answer;
--    ALTER TABLE tree_hole_messages DROP INDEX ft_treehole_question;
