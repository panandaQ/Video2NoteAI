package com.example.server.source;

import java.io.File;

/**
 * 一次 Unit 获取的本地产物。
 *
 * <p>对象存储写入由调用方在计算 {@code contentHash} 之后完成，这样“落盘、写库、投递默认笔记”的
 * 先后顺序由导入模块统一控制，Adapter 不感知 MinIO。
 *
 * <p>{@code file} 位于任务临时目录，调用方必须在 {@code finally} 中删除，禁止把临时路径写进数据库。
 */
public record AcquiredMedia(
        File file,
        String contentType,
        long sizeBytes
) {
    public AcquiredMedia {
        if (file == null) throw new IllegalArgumentException("acquired media file is required");
        if (sizeBytes < 0) throw new IllegalArgumentException("acquired media size cannot be negative");
        contentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;
    }
}
