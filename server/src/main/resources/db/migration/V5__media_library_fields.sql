-- 模块一：让"URL 导入的视频"在本地库里是完整条目
--
-- 背景：导入的视频此前只有文件名与状态——封面从未写入（cover_url 恒为 NULL），
-- 文件大小算出来就被丢弃，时长/UP 主只留在来源字段里但列表不返回。
-- 用户在媒体列表里看到的是一个没有缩略图、没有时长、没有来源信息的空壳。
--
-- 两类变更：
-- 1. source_cover_url  平台封面地址；封面会被下载到 MinIO 并写成 cover_url，
--    因此这个列只是"抓取来源"的快照，不是展示地址（B 站图片有 Referer 防盗链，不能直接热链）；
-- 2. file_size         受管对象字节数，用于库容量展示与排查（此前只在日志里）。
--
-- 兼容：两列都可空，旧上传记录保持 NULL，不影响既有查询与列表。

ALTER TABLE media_files
    ADD COLUMN source_cover_url VARCHAR(1024) NULL,
    ADD COLUMN file_size BIGINT NULL;
