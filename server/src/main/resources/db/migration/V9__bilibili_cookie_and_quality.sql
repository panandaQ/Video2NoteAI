-- 模块一 · 用户级 B 站 Cookie 与导入清晰度
--
-- 1) user_bilibili_credentials：用户粘贴的 B 站 Cookie 的加密落库。Cookie 是登录凭据，
--    必须 AES-GCM 加密后才写入；明文、密钥、密文一律不进日志、不进 MQ 消息、不进导入 Descriptor。
--    每个用户至多一条（user_id 主键），保存 = 覆盖。
--
-- 2) media_files.requested_quality：该用户本次导入为该媒体选择的清晰度（高度像素 360/480/720/1080）。
--    清晰度非敏感，可明文落库；获取阶段直接读媒体行，重试与恢复扫描天然沿用同一取值。
--    只在新建媒体时写入；复用（已下载）媒体不再改写。

CREATE TABLE user_bilibili_credentials (
    user_id BIGINT NOT NULL,
    cookie_encrypted TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE media_files
    ADD COLUMN requested_quality INT NULL;
