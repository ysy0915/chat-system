-- 情绪树洞独立数据表，与 messages 表完全隔离
CREATE TABLE IF NOT EXISTS `tree_hole_messages` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `req_id`      VARCHAR(64)  NOT NULL UNIQUE COMMENT '请求唯一ID',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID',
    `question`    TEXT         NOT NULL COMMENT '用户输入内容',
    `answer_json` MEDIUMTEXT   DEFAULT NULL COMMENT 'AI回答内容',
    `status`      VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending/done/error',
    `mood`        VARCHAR(50)  DEFAULT NULL COMMENT '情绪标签',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_created` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情绪树洞对话记录';
