-- 模块一 · 补 V9 遗漏：导入父任务也要记录用户选择的清晰度
--
-- V9 只给 media_files 加了 requested_quality，漏了 video_import_jobs 这一中间落点，
-- 导致 ImportJobService.createJob 插入父任务时报 Unknown column 'requested_quality'。
-- 该列是「请求 → 父任务 → 媒体」清晰度透传的中间存储，非敏感，可明文落库。

ALTER TABLE video_import_jobs
    ADD COLUMN requested_quality INT NULL;
