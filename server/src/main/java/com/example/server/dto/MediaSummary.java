package com.example.server.dto;

import com.example.server.entity.MediaFile;

import java.time.LocalDateTime;

/**
 * 媒体列表条目。
 *
 * <p>URL 导入的视频在这里必须是一个完整条目：封面、时长、UP 主、来源平台与原链接、文件大小。
 * 这些字段对旧本地上传记录为 {@code null}（那时没有来源概念），前端按缺失处理即可。
 *
 * <p>{@code coverUrl} 必须是对外可读的地址。对象存储桶是私有的（匿名访问返回 403），
 * 因此受管封面要通过 {@link #from(MediaFile, String)} 传入预签名地址；{@link #from(MediaFile)}
 * 只用于没有对象存储可用的场景（旧上传记录、单元测试）。
 */
public record MediaSummary(
        Long id,
        String filename,
        String status,
        String coverUrl,
        LocalDateTime uploadTime,
        String title,
        String author,
        Long durationMs,
        String platform,
        String sourceUrl,
        Long fileSize
) {
    public static MediaSummary from(MediaFile mediaFile) {
        return from(mediaFile, mediaFile.getCoverUrl());
    }

    /** @param displayCoverUrl 对外可读的封面地址（受管对象应传预签名地址） */
    public static MediaSummary from(MediaFile mediaFile, String displayCoverUrl) {
        String title = mediaFile.getSourceTitle() == null || mediaFile.getSourceTitle().isBlank()
                ? mediaFile.getFilename() : mediaFile.getSourceTitle();
        return new MediaSummary(
                mediaFile.getId(),
                mediaFile.getFilename(),
                mediaFile.getStatus() == null ? null : mediaFile.getStatus().name(),
                displayCoverUrl,
                mediaFile.getUploadTime(),
                title,
                mediaFile.getSourceAuthor(),
                mediaFile.getSourceDurationMs(),
                mediaFile.getPlatform() == null ? null : mediaFile.getPlatform().name(),
                mediaFile.getCanonicalUrl(),
                mediaFile.getFileSize());
    }
}
