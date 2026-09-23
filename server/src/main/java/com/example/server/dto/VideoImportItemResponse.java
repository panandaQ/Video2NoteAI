package com.example.server.dto;

/**
 * 导入子项视图。
 *
 * @param stage 可空投影：只用于展示 AI 内部位置，不是第二套业务状态（契约 §3.2）
 */
public record VideoImportItemResponse(
        Long mediaId,
        int itemOrder,
        boolean reused,
        String title,
        String canonicalUrl,
        MediaImportStatus status,
        TaskStage stage,
        boolean retryable,
        String errorCode
) {
}
