package com.example.server.service;

import com.example.server.entity.MediaFile;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 回归保护：URL 导入链路的「下载 → 哈希 → 上传 → 落库 → 临时文件清理」。
 *
 * <p>新的 Unit 获取 Consumer 会原样复用 {@link YtDlpUtils#downloadVideo(String)}、
 * {@link MediaService#calculateMd5(File)} 与 {@link MinioUtils#uploadLocalFile(File)}，
 * 所以在改动入口之前先把现有语义固定下来：临时文件无论成功失败都必须清理，
 * 失败时不得落库，也不得让 MinIO 留下无主对象。
 */
class MediaIngestServiceTest {

    private static final String VIDEO_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    private static final Long USER_ID = 7L;

    @TempDir
    Path tempDir;

    private final YtDlpUtils ytDlpUtils = mock(YtDlpUtils.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final MediaService mediaService = mock(MediaService.class);
    private final MediaIngestService service =
            new MediaIngestService(minioUtils, ytDlpUtils, mediaService);

    @Test
    void deletesTemporaryFileAndKeepsDownloadedBytesUntilUpload() throws Exception {
        Path downloaded = downloadedFile();
        MediaFile saved = new MediaFile();
        saved.setId(42L);
        when(ytDlpUtils.downloadVideo(VIDEO_URL)).thenReturn(downloaded.toFile());
        when(mediaService.calculateMd5(any(File.class))).thenReturn(MD5);
        when(minioUtils.uploadLocalFile(any(File.class))).thenReturn("http://minio/media/object.mp4");
        when(mediaService.saveUploadedMedia(anyString(), anyString(), eq(USER_ID), anyString()))
                .thenReturn(saved);

        MediaFile result = service.ingestUrl(VIDEO_URL, USER_ID);

        assertSame(saved, result);
        verify(mediaService).calculateMd5(eq(downloaded.toFile()));
        verify(minioUtils).uploadLocalFile(eq(downloaded.toFile()));
        verify(mediaService).saveUploadedMedia(
                eq("WEB_source.mp4"), eq("http://minio/media/object.mp4"), eq(USER_ID), eq(MD5));
        assertFalse(Files.exists(downloaded), "临时下载文件必须在返回前删除");
    }

    @Test
    void failedUploadLeavesNoRecordAndStillCleansTemporaryFile() throws Exception {
        Path downloaded = downloadedFile();
        when(ytDlpUtils.downloadVideo(VIDEO_URL)).thenReturn(downloaded.toFile());
        when(mediaService.calculateMd5(any(File.class))).thenReturn(MD5);
        when(minioUtils.uploadLocalFile(any(File.class)))
                .thenThrow(new IllegalStateException("MinIO 文件上传失败"));

        assertThrows(IllegalStateException.class, () -> service.ingestUrl(VIDEO_URL, USER_ID));

        verify(mediaService, never()).saveUploadedMedia(anyString(), anyString(), any(), anyString());
        assertFalse(Files.exists(downloaded), "上传失败也必须清理临时文件");
    }

    @Test
    void failedHashingSkipsUploadAndStillCleansTemporaryFile() throws Exception {
        Path downloaded = downloadedFile();
        when(ytDlpUtils.downloadVideo(VIDEO_URL)).thenReturn(downloaded.toFile());
        when(mediaService.calculateMd5(any(File.class))).thenThrow(new IOException("读取失败"));

        assertThrows(IOException.class, () -> service.ingestUrl(VIDEO_URL, USER_ID));

        verify(minioUtils, never()).uploadLocalFile(any(File.class));
        verify(mediaService, never()).saveUploadedMedia(anyString(), anyString(), any(), anyString());
        assertFalse(Files.exists(downloaded), "哈希失败也必须清理临时文件");
    }

    @Test
    void failedDownloadPropagatesWithoutTouchingStorage() throws Exception {
        when(ytDlpUtils.downloadVideo(VIDEO_URL))
                .thenThrow(new IllegalStateException("视频链接下载超时"));

        assertThrows(IllegalStateException.class, () -> service.ingestUrl(VIDEO_URL, USER_ID));

        verify(minioUtils, never()).uploadLocalFile(any(File.class));
        verify(mediaService, never()).saveUploadedMedia(anyString(), anyString(), any(), anyString());
    }

    @Test
    void blankUrlIsRejectedBeforeAnyDownload() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.ingestUrl("   ", USER_ID));

        verify(ytDlpUtils, never()).downloadVideo(anyString());
        verify(minioUtils, never()).uploadLocalFile(any(File.class));
    }

    private Path downloadedFile() throws IOException {
        return Files.writeString(tempDir.resolve("source.mp4"), "video-bytes");
    }
}
