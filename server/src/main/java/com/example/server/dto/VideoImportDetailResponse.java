package com.example.server.dto;

import com.example.server.source.ImportTargetType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入任务详情。
 *
 * <p>{@code traceId} 是内部运维字段，不出现在响应中。单次最多 50 个子项，首期不分页。
 */
public record VideoImportDetailResponse(
        Long importId,
        VideoImportJobStatus status,
        ImportTargetType targetType,
        String platform,
        ContainerSummary container,
        ImportCounts counts,
        boolean retryable,
        String errorCode,
        String errorMessage,
        List<VideoImportItemResponse> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
