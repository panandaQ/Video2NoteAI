package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.MediaImportStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 导入父任务与媒体单元的关联，携带本次解析的顺序快照。
 *
 * <p>主键是 {@code (import_id, media_id)} 复合键，因此不使用自增主键；所有状态变更都通过条件更新完成，
 * 只有真实发生状态转换时才允许改动父任务计数。该表是父任务计数的事实来源。
 */
@Data
@TableName("video_import_items")
public class VideoImportItem {

    private Long importId;
    private Long mediaId;
    private Integer itemOrder;

    /** 解析时是否复用了已有媒体记录；与最终完成数是正交指标。 */
    private Boolean reused;

    private MediaImportStatus itemStatus;
    private Boolean retryable;
    private String errorCode;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
