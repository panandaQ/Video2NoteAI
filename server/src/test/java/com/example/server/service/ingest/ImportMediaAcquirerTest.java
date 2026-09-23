package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.MediaFile;
import com.example.server.service.MediaService;
import com.example.server.source.AcquiredMedia;
import com.example.server.source.SourceAdapterRegistry;
import com.example.server.source.VideoPlatform;
import com.example.server.source.VideoSourceAdapter;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体入库边界：对象命名确定性、内容哈希、封面转存与"封面失败不影响导入"。
 *
 * <p>封面是本地库条目的展示字段，但它绝不能成为导入的失败原因：B 站图片有防盗链、可能 404、
 * 可能返回异常大的响应。这里固定住降级行为。
 */
class ImportMediaAcquirerTest {

    private static final Long MEDIA_ID = 22L;
    private static final Long USER_ID = 7L;
    private static final String CANONICAL_URL = "https://www.bilibili.com/video/BV1xx411c7mD";
    private static final String COVER_URL = "https://i0.hdslb.com/bfs/archive/cover.jpg";

    @TempDir
    Path tempDir;

    /**
     * {@code SourceAdapterRegistry} 是 final 类（subclass mock maker 无法模拟），因此用真实现 +
     * 可编程的假 Adapter：这也更接近生产装配——Registry 只按 supports 选择，行为由 Adapter 决定。
     */
    private final FakeAdapter adapter = new FakeAdapter();
    private final SourceAdapterRegistry registry = new SourceAdapterRegistry(List.of(adapter));
    private final MediaService mediaService = mock(MediaService.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final VideoImportProperties properties = new VideoImportProperties();

    private final ImportMediaAcquirer acquirer =
            new ImportMediaAcquirer(registry, mediaService, minioUtils, properties);

    @Test
    void storesVideoAndCoverUnderDeterministicObjectNames() throws Exception {
        Path file = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(file.toFile(), "video/mp4", 11L);
        adapter.cover = new VideoSourceAdapter.AcquiredCover(new byte[]{1, 2, 3, 4}, "image/jpeg", ".jpg");
        when(mediaService.calculateMd5(any(java.io.File.class))).thenReturn("hash");
        when(minioUtils.uploadObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> "http://minio/media/" + invocation.getArgument(0, String.class));

        ImportMediaAcquirer.StoredMedia stored = acquirer.acquireAndStore(media());

        assertEquals("http://minio/media/video-import/content/hash/source.mp4", stored.objectUrl());
        assertEquals("http://minio/media/video-import/content/hash/cover.jpg", stored.coverObjectUrl());
        assertEquals("hash", stored.contentHash());
        assertEquals(11L, stored.sizeBytes());
    }

    /** 封面抓取失败只能降级为"没有封面"，媒体本身必须照常入库。 */
    @Test
    void coverFailureDoesNotFailTheAcquisition() throws Exception {
        Path file = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(file.toFile(), "video/mp4", 11L);
        adapter.coverFailure = new IllegalStateException("cover endpoint down");
        when(mediaService.calculateMd5(any(java.io.File.class))).thenReturn("hash");
        when(minioUtils.uploadObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> "http://minio/media/" + invocation.getArgument(0, String.class));

        ImportMediaAcquirer.StoredMedia stored = acquirer.acquireAndStore(media());

        assertEquals("http://minio/media/video-import/content/hash/source.mp4", stored.objectUrl());
        assertNull(stored.coverObjectUrl());
    }

    /** 平台没有封面地址时不应发起任何抓取。 */
    @Test
    void mediaWithoutCoverSourceSkipsCoverFetch() throws Exception {
        Path file = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(file.toFile(), "video/mp4", 11L);
        when(mediaService.calculateMd5(any(java.io.File.class))).thenReturn("hash");
        when(minioUtils.uploadObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenReturn("http://minio/media/video-import/7/22/source.mp4");
        MediaFile media = media();
        media.setSourceCoverUrl(null);

        ImportMediaAcquirer.StoredMedia stored = acquirer.acquireAndStore(media);

        assertNull(stored.coverObjectUrl());
        assertEquals(0, adapter.coverFetches);
    }

    /** 临时文件必须在 finally 清理，无论成功还是失败。 */
    @Test
    void temporaryFileIsDeletedAfterUpload() throws Exception {
        Path file = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(file.toFile(), "video/mp4", 11L);
        when(mediaService.calculateMd5(any(java.io.File.class))).thenReturn("hash");
        when(minioUtils.uploadObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("minio down"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> acquirer.acquireAndStore(media()));

        assertNull(Files.exists(file) ? "still-there" : null, "获取失败也必须清理本地临时文件");
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        media.setStatus(MediaImportStatus.ACQUIRING);
        media.setPlatform(VideoPlatform.BILIBILI);
        media.setResourceType("UGC_VIDEO");
        media.setExternalResourceId("BV1xx411c7mD");
        media.setExternalUnitId("41820686637");
        media.setCanonicalUrl(CANONICAL_URL);
        media.setSourceCoverUrl(COVER_URL);
        return media;
    }

    /**
     * 断言对象名是**内容寻址**的：同一份内容无论被谁、第几次导入，都落在同一个对象上（D-068）。
     *
     * <p>这是"跨用户不重复存储"的机制本身：命名里没有 userId/mediaId，因此两个用户导入同一视频
     * 只会覆盖同一个对象，而不是各存一份。
     */
    @Test
    void objectNameIsDeterministicContentAddress() throws Exception {
        Path file = Files.writeString(tempDir.resolve("unit.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(file.toFile(), "video/mp4", 11L);
        when(mediaService.calculateMd5(any(java.io.File.class))).thenReturn("hash");
        when(minioUtils.uploadObject(anyString(), any(InputStream.class), anyLong(), anyString()))
                .thenAnswer(invocation -> "http://minio/media/" + invocation.getArgument(0, String.class));

        String first = acquirer.acquireAndStore(media()).objectUrl();
        Path again = Files.writeString(tempDir.resolve("unit2.mp4"), "video-bytes");
        adapter.acquired = new AcquiredMedia(again.toFile(), "video/mp4", 11L);
        String second = acquirer.acquireAndStore(media()).objectUrl();

        assertEquals(first, second);
        verify(minioUtils, org.mockito.Mockito.times(2))
                .uploadObject(eq("video-import/content/hash/source.mp4"), any(InputStream.class), eq(11L),
                        eq("video/mp4"));
    }

    /** 可编程的假来源实现：本测试只关心导入编排，不关心平台细节。 */
    private static final class FakeAdapter implements VideoSourceAdapter {

        private AcquiredMedia acquired;
        private VideoSourceAdapter.AcquiredCover cover;
        private RuntimeException coverFailure;
        private int coverFetches;

        @Override
        public boolean supports(java.net.URI input) {
            return true;
        }

        @Override
        public com.example.server.source.VideoImportPlan resolve(java.net.URI input, String cookie) {
            throw new UnsupportedOperationException("本测试不覆盖解析");
        }

        @Override
        public AcquiredMedia acquire(com.example.server.source.VideoSourceUnit source, Integer height, String cookie) {
            return acquired;
        }

        @Override
        public VideoSourceAdapter.AcquiredCover fetchCover(String coverUrl) {
            coverFetches++;
            if (coverFailure != null) {
                throw coverFailure;
            }
            return cover;
        }
    }
}

