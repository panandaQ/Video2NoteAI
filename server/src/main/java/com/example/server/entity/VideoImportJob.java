package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.source.ImportTargetType;
import com.example.server.source.VideoPlatform;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次用户 URL 提交（导入父任务），对外标识为 {@code importId}。
 *
 * <p>{@code active_request_key} 只在非终态时非空并带唯一约束：并发提交同一 URL 会在数据库层收敛到
 * 一个活跃任务，Redis 不可用也不产生重复父任务；进入终态后清空，允许重新提交以发现合集新增单元。
 */
@Data
@TableName("video_import_jobs")
public class VideoImportJob {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String originalUrl;
    private String requestHash;
    private String activeRequestKey;

    private VideoPlatform platform;
    private ImportTargetType targetType;
    private String containerId;
    private String containerTitle;

    /** 用户本次提交选择的清晰度（高度像素）；可空 = 默认 480P。 */
    private Integer requestedQuality;

    private VideoImportJobStatus status;

    private Integer totalCount;
    private Integer reusedCount;
    private Integer completedCount;
    private Integer failedCount;

    private Integer attemptCount;
    private Boolean retryable;
    private String errorCode;
    private String errorMessage;

    /** 内部运维字段：创建父任务时生成，重试复用，API 不回传。 */
    private String traceId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
