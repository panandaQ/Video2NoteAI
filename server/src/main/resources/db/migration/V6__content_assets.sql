-- 模块一 · 个人知识库规格对齐（D-068 / D-070）：跨用户共享的内容资产
--
-- 背景：视频字节原先按用户重复保存（对象名 video-import/{userId}/{mediaId}/source.mp4），
-- 同一个热门视频被 N 个用户导入就有 N 份字节、N 次下载。规格要求视频字节与初始知识快照
-- 作为**内容级资产跨用户共享**，而用户内容库条目（对外仍是 mediaId）保持用户级。
--
-- 设计取舍（先简后繁）：
--   * 只建一张 content_assets，把"内容身份 + 当前字节版本"放在一起。规格里的
--     ContentAsset / ContentVersion 两张表是为"强制刷新产生多版本"准备的，而强制刷新
--     已明确推迟（D-070），此时拆两张表只会多一次 join 而没有收益；将来要做刷新时，
--     把 content_hash/object_ref/file_size 三列搬到 content_versions 即可，媒体行不动。
--   * 用户内容库条目直接复用 media_files：对外 mediaId 不变，权限、列表、删除、笔记
--     全部沿用现有按 user_id 的链路，不引入第二套"用户-内容"引用表。
--   * 不建外键：删除与清理仍由应用服务显式处理（沿用 V1–V5 风格）。
CREATE TABLE content_assets (
    id BIGINT NOT NULL AUTO_INCREMENT,
    platform VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    external_resource_id VARCHAR(128) NOT NULL,
    external_unit_id VARCHAR(128) NOT NULL,
    canonical_url VARCHAR(2048) NULL,
    title VARCHAR(255) NULL,
    author VARCHAR(255) NULL,
    duration_ms BIGINT NULL,
    -- 当前字节版本：下载完成前为 NULL；首个写入者胜出，后来者只读复用（见 publishBytes 的 CAS 条件）
    content_hash VARCHAR(64) NULL,
    object_ref VARCHAR(1024) NULL,
    cover_ref VARCHAR(1024) NULL,
    file_size BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    -- 内容身份：与 media_files.uk_media_user_source_unit 的四段一致，只是去掉了 user_id
    UNIQUE KEY uk_content_asset_identity
        (platform, resource_type, external_resource_id, external_unit_id),
    KEY idx_content_asset_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 用户条目指向共享资产；旧上传记录与改动前的 URL 记录保持 NULL（不回溯迁移，
-- 它们各自持有的对象引用仍然有效：file_path 是自洽的，读取路径不依赖 asset）。
-- 因此该列可空，且缺列值时按"没有共享资产"降级——旧数据不需要停机回填。
ALTER TABLE media_files
    ADD COLUMN content_asset_id BIGINT NULL,
    ADD KEY idx_media_content_asset (content_asset_id);
