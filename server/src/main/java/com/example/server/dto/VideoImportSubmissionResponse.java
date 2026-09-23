package com.example.server.dto;

import com.example.server.entity.VideoImportJob;
import com.example.server.source.ImportTargetType;

import java.time.LocalDateTime;

/**
 * 创建或重试导入任务的受理结果。
 *
 * <p>{@code traceId} 是内部运维字段，不进入本响应：客户端只需要用 {@code importId} 查询状态。
 */
public record VideoImportSubmissionResponse(
        Long importId,
        VideoImportJobStatus status,
        ImportTargetType targetType,
        boolean reused,
        LocalDateTime submittedAt
) {

    public static VideoImportSubmissionResponse of(VideoImportJob job, boolean reused) {
        return new VideoImportSubmissionResponse(
                job.getId(),
                job.getStatus(),
                job.getTargetType(),
                reused,
                job.getCreatedAt() == null ? LocalDateTime.now() : job.getCreatedAt());
    }
}
