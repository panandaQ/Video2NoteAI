-- 模块一：在线视频 URL 异步导入
--
-- 三类变更：
-- 1. video_import_jobs  一次 URL 提交（父任务），对外标识 importId；
-- 2. video_import_items 父任务与媒体单元的多对多关联和解析顺序快照；
-- 3. media_files        增加来源身份、获取/笔记错误字段，并放开 file_path 非空约束。
--
-- 不新增外键，删除与清理由应用服务按用户归属显式处理（沿用 V1-V3 风格）。

CREATE TABLE video_import_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    active_request_key CHAR(64) NULL,
    platform VARCHAR(32) NULL,
    target_type VARCHAR(16) NOT NULL DEFAULT 'DETECTING',
    container_id VARCHAR(128) NULL,
    container_title VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL DEFAULT 0,
    reused_count INT NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    attempt_count INT NOT NULL DEFAULT 0,
    retryable TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    trace_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_video_import_active_request (active_request_key),
    KEY idx_video_import_user_time (user_id, created_at),
    KEY idx_video_import_status_time (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE video_import_items (
    import_id BIGINT NOT NULL,
    media_id BIGINT NOT NULL,
    item_order INT NOT NULL,
    reused TINYINT(1) NOT NULL DEFAULT 0,
    item_status VARCHAR(32) NOT NULL,
    retryable TINYINT(1) NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (import_id, media_id),
    KEY idx_video_import_item_media (media_id),
    KEY idx_video_import_item_status (import_id, item_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 来源字段对旧上传记录保持 NULL；MySQL 唯一索引允许多个 NULL，
-- 因此旧数据不会与新 URL 记录冲突。
-- file_path 必须放开：URL 媒体在 MEDIA_READY 之前没有对象地址，禁止写伪路径占位。
ALTER TABLE media_files
    MODIFY file_path VARCHAR(1024) NULL,
    ADD COLUMN platform VARCHAR(32) NULL,
    ADD COLUMN resource_type VARCHAR(32) NULL,
    ADD COLUMN external_resource_id VARCHAR(128) NULL,
    ADD COLUMN external_unit_id VARCHAR(128) NULL,
    ADD COLUMN canonical_url VARCHAR(2048) NULL,
    ADD COLUMN source_title VARCHAR(255) NULL,
    ADD COLUMN source_author VARCHAR(255) NULL,
    ADD COLUMN source_duration_ms BIGINT NULL,
    ADD COLUMN acquire_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN acquire_retryable TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN acquire_error_code VARCHAR(64) NULL,
    ADD COLUMN acquire_error_message VARCHAR(500) NULL,
    ADD COLUMN note_profile_version VARCHAR(32) NULL,
    ADD COLUMN note_attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN note_retryable TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN note_error_code VARCHAR(64) NULL,
    ADD COLUMN note_error_message VARCHAR(500) NULL,
    ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    ADD UNIQUE KEY uk_media_user_source_unit
        (user_id, platform, resource_type, external_resource_id, external_unit_id),
    ADD KEY idx_media_status_updated (status, updated_at);
