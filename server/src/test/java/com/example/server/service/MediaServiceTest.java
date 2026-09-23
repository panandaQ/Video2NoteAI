package com.example.server.service;

import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.InOrder;

/**
 * P0 回归保护：媒体落库的 MinIO 回滚、内容哈希与文件名归一。
 *
 * <p>{@link MediaService#calculateMd5(java.io.File)} 和
 * {@link MediaService#normalizeVideoFilename(String)} 会被 URL 导入链路复用；
 * {@link MediaService#saveUploadedMedia} 的「落库失败即删除已上传对象」语义也必须保持，
 * 否则异步获取失败时会在 MinIO 留下无主对象。URL 链路新增的落库方法必须延续同一套回滚规则。
 */
class MediaServiceTest {

    private static final String OBJECT_URL = "http://minio/media/object.mp4";
    private static final String MD5_OF_HELLO = "5d41402abc4b2a76b9719d911017c592";
    private static final String HASH = "md5-value";

    @TempDir
    Path tempDir;

    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final MediaDeletionListener deletionListener = mock(MediaDeletionListener.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    @Test
    void md5MatchesFileContent() throws Exception {
        Path file = Files.writeString(tempDir.resolve("clip.mp4"), "hello");

        assertEquals(MD5_OF_HELLO, newService().calculateMd5(file.toFile()));
    }

    @Test
    void removesUploadedObjectWhenInsertFails() {
        MediaService service = newService();
        when(mediaFileMapper.insert(any(MediaFile.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> service.saveUploadedMedia("WEB_source.mp4", OBJECT_URL, 7L, HASH));

        verify(minioUtils).removeFile(OBJECT_URL);
    }

    @Test
    void keepsOriginalErrorWhenRollbackAlsoFails() {
        MediaService service = newService();
        RuntimeException persistError = new IllegalStateException("db down");
        when(mediaFileMapper.insert(any(MediaFile.class))).thenThrow(persistError);
        doThrow(new IllegalStateException("minio down")).when(minioUtils).removeFile(OBJECT_URL);

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> service.saveUploadedMedia("WEB_source.mp4", OBJECT_URL, 7L, HASH));

        assertSame(persistError, thrown, "回滚失败不能顶替原始持久化异常");
        assertEquals(1, thrown.getSuppressed().length, "回滚异常必须以 suppressed 形式保留");
    }

    @Test
    void successfulPersistKeepsObjectAndCachesHash() {
        MediaService service = newService();
        when(mediaFileMapper.insert(any(MediaFile.class))).thenAnswer(invocation -> {
            MediaFile entity = invocation.getArgument(0);
            entity.setId(11L);
            return 1;
        });

        MediaFile saved = service.saveUploadedMedia("WEB_source.mp4", OBJECT_URL, 7L, HASH);

        assertEquals(11L, saved.getId());
        assertEquals(MediaImportStatus.COMPLETED, saved.getStatus());
        assertEquals(OBJECT_URL, saved.getFilePath());
        verify(valueOperations).set("media:md5:11", HASH);
        verify(minioUtils, never()).removeFile(anyString());
    }

    @Test
    void normalizeVideoFilenameKeepsBasenameAndRejectsNonVideo() {
        MediaService service = newService();

        assertEquals("clip.mp4", service.normalizeVideoFilename("C:\\tmp\\clip.mp4"));
        assertThrows(IllegalArgumentException.class, () -> service.normalizeVideoFilename("notes.txt"));
    }

    private MediaService newService() {
        return newServiceWith(List.of());
    }

    private MediaService newServiceWith(List<MediaDeletionListener> listeners) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        return new MediaService(mediaFileMapper, redisTemplate, minioUtils, new ObjectMapper(),
                mock(AgentCheckpointService.class), mock(AgentTelemetry.class),
                mock(QdrantVectorStore.class), mock(VideoContextService.class), listeners);
    }

    /**
     * 删除媒体必须先让引用它的业务记录收敛，再删除媒体行（契约 §9.4 / AC-21）。
     *
     * <p>D-070 起**不再删除对象**：视频字节与封面是内容寻址的共享对象，可能正被其他用户引用，
     * 删掉它等于"删自己那条，顺手删了别人的视频"。即使零引用也不自动清理（规格 §3.2 / AC-16）。
     */
    @Test
    void deleteNotifiesListenersBeforeRemovingMediaRowAndKeepsSharedObject() {
        MediaService service = newServiceWith(List.of(deletionListener));
        when(mediaFileMapper.selectById(5L)).thenReturn(media(5L, OBJECT_URL));

        service.deleteOwnedMedia(5L, 7L);

        InOrder order = inOrder(deletionListener, mediaFileMapper);
        order.verify(deletionListener).beforeMediaDeleted(5L, 7L);
        order.verify(mediaFileMapper).deleteById(5L);
        verify(minioUtils, never()).removeFile(anyString());
    }

    /** 下游记账失败不能阻止用户删除自己的媒体；遗留悬挂记录由恢复扫描兜底。 */
    @Test
    void deleteProceedsWhenListenerFails() {
        MediaService service = newServiceWith(List.of(deletionListener));
        when(mediaFileMapper.selectById(5L)).thenReturn(media(5L, OBJECT_URL));
        doThrow(new IllegalStateException("import bookkeeping down"))
                .when(deletionListener).beforeMediaDeleted(5L, 7L);

        service.deleteOwnedMedia(5L, 7L);

        verify(mediaFileMapper).deleteById(5L);
    }

    private MediaFile media(Long id, String filePath) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setUserId(7L);
        media.setStatus(MediaImportStatus.READY);
        media.setFilePath(filePath);
        return media;
    }
}
