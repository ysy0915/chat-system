-- 为 messages 表新增 summary 字段，用于存储对话摘要（自动标题）
ALTER TABLE messages ADD COLUMN summary VARCHAR(200) NULL COMMENT '对话摘要（AI 自动生成）' AFTER question;
