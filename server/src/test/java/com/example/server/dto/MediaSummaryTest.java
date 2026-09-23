package com.example.server.dto;

import com.example.server.entity.MediaFile;
import com.example.server.source.VideoPlatform;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 媒体列表条目契约。
 *
 * <p>URL 导入的视频必须是一个完整条目：封面、时长、UP 主、来源平台与原链接、文件大小；
 * 旧本地上传记录没有这些来源概念，对应字段保持 {@code null}，前端按缺失处理。
 */
class MediaSummaryTest {

    @Test
    void importedMediaExposesLibraryFields() {
        MediaFile media = new MediaFile();
        media.setId(22L);
        media.setFilename("谁说傲娇退环境了");
        media.setStatus(MediaImportStatus.READY);
        media.setCoverUrl("http://minio/media/video-import/1/22/cover.jpg");
        media.setUploadTime(LocalDateTime.of(2026, 9, 17, 15, 0));
        media.setSourceTitle("谁说傲娇退环境了");
        media.setSourceAuthor("小西饱饱-");
        media.setSourceDurationMs(13_000L);
        media.setPlatform(VideoPlatform.BILIBILI);
        media.setCanonicalUrl("https://www.bilibili.com/video/BV1LtY968EcB");
        media.setFileSize(6_360_983L);

        MediaSummary summary = MediaSummary.from(media);

        assertEquals("READY", summary.status());
        assertEquals("http://minio/media/video-import/1/22/cover.jpg", summary.coverUrl());
        assertEquals("小西饱饱-", summary.author());
        assertEquals(13_000L, summary.durationMs());
        assertEquals("BILIBILI", summary.platform());
        assertEquals("https://www.bilibili.com/video/BV1LtY968EcB", summary.sourceUrl());
        assertEquals(6_360_983L, summary.fileSize());
    }

    /** 旧上传记录：来源字段为空时标题回退到文件名，其余保持 null。 */
    @Test
    void legacyUploadFallsBackToFilenameAndNullSourceFields() {
        MediaFile media = new MediaFile();
        media.setId(2L);
        media.setFilename("WEB_source.mp4");
        media.setStatus(MediaImportStatus.COMPLETED);

        MediaSummary summary = MediaSummary.from(media);

        assertEquals("WEB_source.mp4", summary.title());
        assertNull(summary.author());
        assertNull(summary.durationMs());
        assertNull(summary.platform());
        assertNull(summary.sourceUrl());
        assertNull(summary.fileSize());
        assertNull(summary.coverUrl());
    }

    /** 标题字段存在但为空白时同样回退到文件名，避免列表出现空标题条目。 */
    @Test
    void blankSourceTitleFallsBackToFilename() {
        MediaFile media = new MediaFile();
        media.setId(3L);
        media.setFilename("clip.mp4");
        media.setStatus(MediaImportStatus.READY);
        media.setSourceTitle("   ");

        assertEquals("clip.mp4", MediaSummary.from(media).title());
    }

    /**
     * 桶是私有的，列表必须返回预签名地址而不是裸对象地址，否则浏览器拿到的是一张裂图。
     */
    @Test
    void managedCoverIsReplacedByDisplayUrl() {
        MediaFile media = new MediaFile();
        media.setId(22L);
        media.setFilename("标题");
        media.setStatus(MediaImportStatus.READY);
        media.setCoverUrl("http://localhost:9000/media/video-import/1/22/cover.jpg");

        MediaSummary summary = MediaSummary.from(media,
                "http://localhost:9000/media/video-import/1/22/cover.jpg?X-Amz-Signature=abc");

        assertEquals("http://localhost:9000/media/video-import/1/22/cover.jpg?X-Amz-Signature=abc",
                summary.coverUrl());
    }
}
