package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.MediaImportStatus;
import com.example.server.source.VideoPlatform;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 一个可独立获取、处理和问答的内容单元。
 *
 * <p>V4 起该表同时承载两种记录：
 * <ul>
 *   <li>旧本地上传记录：来源字段为空，{@code status = COMPLETED}；</li>
 *   <li>URL 导入记录：四段来源身份必填，状态沿导入状态链推进到 {@code READY}。</li>
 * </ul>
 * {@code file_path} 对 URL 记录在 {@code MEDIA_READY} 之前为空，禁止写伪路径占位。
 */
@Data
@TableName("media_files")
public class MediaFile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String filename;
    private MediaImportStatus status;
    private String filePath;
    private String contentHash;

    private String aiSummary;
    private String transcriptText;
    private String coverUrl;

    private LocalDateTime uploadTime;

    // ---- 来源身份（V4）：旧上传记录保持为空，新 URL 记录必须写齐四段 ----
    private VideoPlatform platform;
    private String resourceType;
    private String externalResourceId;
    private String externalUnitId;
    private String canonicalUrl;
    private String sourceTitle;
    private String sourceAuthor;
    private Long sourceDurationMs;

    /** 跨用户共享的内容资产（V6 / D-068）；旧上传记录与改动前的 URL 记录为 null。 */
    private Long contentAssetId;

    /**
     * 平台封面地址（V5）。只是抓取来源的快照：抓到的图片会被下载到 MinIO 并写进 {@link #coverUrl}，
     * 前端永远读受管对象地址——B 站图片有 Referer 防盗链，直接热链在浏览器里会 403。
     */
    private String sourceCoverUrl;

    /** 受管对象字节数（V5）。获取时算出来，写入成功时落库，用于库容量展示与排查。 */
    private Long fileSize;

    /** 用户本次导入为该媒体选择的清晰度（高度像素）；可空 = 默认 480P。 */
    private Integer requestedQuality;

    // ---- Unit 获取真源（V4）----
    private Integer acquireAttemptCount;
    private Boolean acquireRetryable;
    private String acquireErrorCode;
    private String acquireErrorMessage;

    // ---- 默认笔记真源（V4）----
    private String noteProfileVersion;
    private Integer noteAttemptCount;
    private Boolean noteRetryable;
    private String noteErrorCode;
    private String noteErrorMessage;

    /** 恢复扫描判断处理中状态是否超时；不能用 {@code upload_time} 代替。 */
    private LocalDateTime updatedAt;
}
