-- 媒体生成记录表（图片/视频/3D模型）
CREATE TABLE IF NOT EXISTS media_gen_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '用户ID',
    prompt VARCHAR(2000) NOT NULL COMMENT '用户输入的提示词',
    media_type VARCHAR(20) NOT NULL COMMENT '类型: image / video / 3d',
    model VARCHAR(100) COMMENT '使用的模型名称',
    media_url VARCHAR(2048) COMMENT 'OSS 上的主文件 URL',
    glb_url VARCHAR(2048) COMMENT '3D GLB 文件 URL（仅3D）',
    obj_url VARCHAR(2048) COMMENT '3D OBJ 文件 URL（仅3D）',
    preview_url VARCHAR(2048) COMMENT '预览图 URL（3D模型预览图）',
    status VARCHAR(20) DEFAULT 'done' COMMENT '状态: done / error',
    error_msg VARCHAR(500) COMMENT '错误信息（失败时）',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='媒体生成记录';
