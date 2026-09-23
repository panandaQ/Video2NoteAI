package com.example.server.dto;

/**
 * 父任务计数。事实来源是 {@code video_import_items}，父表字段只是查询优化。
 *
 * @param total     本次解析的单元总数
 * @param reused    解析时复用了已有媒体记录的单元数，与完成数正交
 * @param completed 默认视频笔记已完成的单元数
 * @param failed    最终失败的单元数
 */
public record ImportCounts(
        int total,
        int reused,
        int completed,
        int failed
) {
}
