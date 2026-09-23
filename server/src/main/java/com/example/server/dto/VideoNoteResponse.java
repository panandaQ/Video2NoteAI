package com.example.server.dto;

/**
 * 默认视频笔记视图。
 *
 * <p>结果真源是 {@code agent_checkpoints} 中 {@code VIDEO_NOTE_V1 + GENERAL} 的结构化
 * {@link AnalysisResult}；任务未完成时 {@code note} 为 {@code null}，接口仍返回 200 与当前状态。
 *
 * @param profileVersion 生成该笔记的任务身份版本，便于版本升级后识别旧结果
 */
public record VideoNoteResponse(
        Long mediaId,
        MediaImportStatus status,
        TaskStage stage,
        String profileVersion,
        AnalysisResult note,
        boolean retryable,
        String errorCode
) {
}
