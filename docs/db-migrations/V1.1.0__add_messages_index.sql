-- V1.1.0 添加messages表联合索引（优化搜索查询排序）
-- 优化前：SELECT * FROM messages WHERE is_private = 1 ORDER BY created_at DESC 需要全表扫描
-- 优化后：使用联合索引 (is_private, created_at) 高效查询

ALTER TABLE messages ADD INDEX idx_private_created (is_private, created_at);
