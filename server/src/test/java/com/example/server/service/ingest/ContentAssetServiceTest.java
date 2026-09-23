package com.example.server.service.ingest;

import com.example.server.dto.ContentAssetStatus;
import com.example.server.entity.ContentAsset;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.ContentAssetMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.source.VideoPlatform;
import com.example.server.source.VideoSourceUnit;
import com.example.server.utils.MinioUtils;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 共享内容资产契约（D-068）。
 *
 * <p>这里固定三件事：① 身份唯一，并发登记靠唯一约束收敛；② 命中共享字节时把对象引用挂到用户条目上
 * （这就是"不重复下载"的机制）；③ 数据库里的引用与对象存储不一致时退回下载，绝不返回坏引用。
 */
class ContentAssetServiceTest {

    private static final Long MEDIA_ID = 21L;
    private static final Long ASSET_ID = 5L;
    private static final String OBJECT_REF = "http://minio/media/video-import/content/hash/source.mp4";

    private final ContentAssetMapper assetMapper = mock(ContentAssetMapper.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final MinioUtils minioUtils = mock(MinioUtils.class);
    private final ContentAssetReadyNotifier readyNotifier = mock(ContentAssetReadyNotifier.class);

    private final ContentAssetService service =
            new ContentAssetService(assetMapper, mediaFileMapper, minioUtils, readyNotifier);

    @Test
    void attachCreatesAssetOnceAndBindsUserEntry() {
        when(assetMapper.findBySourceUnit(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
        when(assetMapper.insert(any(ContentAsset.class))).thenAnswer(invocation -> {
            ContentAsset asset = invocation.getArgument(0);
            asset.setId(ASSET_ID);
            return 1;
        });
        MediaFile media = media();

        ContentAsset asset = service.attach(media, unit());

        assertEquals(ASSET_ID, asset.getId());
        assertEquals(ContentAssetStatus.PROCESSING, asset.getStatus(), "新资产还没有字节");
        verify(mediaFileMapper).bindContentAsset(MEDIA_ID, ASSET_ID);
        assertEquals(ASSET_ID, media.getContentAssetId());
    }

    /** 两个用户同时导入同一个新视频：唯一约束赢了的一端建行，另一端回查复用同一行。 */
    @Test
    void attachReusesAssetCreatedByConcurrentRegistration() {
        ContentAsset existing = asset(OBJECT_REF);
        when(assetMapper.findBySourceUnit(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null, existing);
        when(assetMapper.insert(any(ContentAsset.class))).thenThrow(new DuplicateKeyException("uk_content_asset_identity"));

        ContentAsset asset = service.attach(media(), unit());

        assertEquals(ASSET_ID, asset.getId());
    }

    @Test
    void sharedBytesAreAdoptedWhenObjectExists() {
        ContentAsset asset = asset(OBJECT_REF);
        when(mediaFileMapper.adoptSharedBytes(MEDIA_ID, ASSET_ID, OBJECT_REF, "hash", 100L, "cover"))
                .thenReturn(1);
        when(minioUtils.objectExists(OBJECT_REF)).thenReturn(true);
        MediaFile media = media();

        assertTrue(service.adoptSharedBytes(media, asset));

        assertEquals(OBJECT_REF, media.getFilePath());
        assertEquals("hash", media.getContentHash());
        assertEquals(100L, media.getFileSize());
        assertEquals("cover", media.getCoverUrl());
    }

    /** 引用与对象存储不一致（人工删除、桶迁移）时必须退回下载，而不是让后续步骤读 404 对象。 */
    @Test
    void missingObjectFallsBackToDownload() {
        ContentAsset asset = asset(OBJECT_REF);
        when(minioUtils.objectExists(OBJECT_REF)).thenReturn(false);
        MediaFile media = media();

        assertFalse(service.adoptSharedBytes(media, asset));

        verify(mediaFileMapper, never()).adoptSharedBytes(anyLong(), anyLong(), anyString(), anyString(),
                anyLong(), anyString());
    }

    /** 条目已经有自己的对象引用（本次下载刚写入）时不覆盖。 */
    @Test
    void ownObjectReferenceIsNeverOverwritten() {
        MediaFile media = media();
        media.setFilePath("http://minio/media/video-import/content/mine/source.mp4");

        assertFalse(service.adoptSharedBytes(media, asset(OBJECT_REF)));

        verify(minioUtils, never()).objectExists(anyString());
    }

    @Test
    void publishBytesRegistersFirstVersionOnly() {
        when(assetMapper.publishBytes(ASSET_ID, "hash", OBJECT_REF, "cover", 100L)).thenReturn(1);

        service.publishBytes(ASSET_ID, "hash", OBJECT_REF, "cover", 100L);

        verify(assetMapper).publishBytes(ASSET_ID, "hash", OBJECT_REF, "cover", 100L);
        verify(assetMapper, never()).publishCover(anyLong(), anyString());
        // 字节是这次发布的 → 广播一次，唤醒正在等这份内容的用户。
        verify(readyNotifier).publish(ASSET_ID);
    }

    /** 已有版本时不覆盖（字节不可变），但允许补写缺失的封面；也不能再广播一次。 */
    @Test
    void publishBytesKeepsExistingVersionAndBackfillsCover() {
        when(assetMapper.publishBytes(ASSET_ID, "hash2", OBJECT_REF, "cover", 100L)).thenReturn(0);

        service.publishBytes(ASSET_ID, "hash2", OBJECT_REF, "cover", 100L);

        verify(assetMapper).publishCover(ASSET_ID, "cover");
        verify(readyNotifier, never()).publish(anyLong());
    }

    /** 登记失败不影响本次导入：字节已经落盘、用户条目已指向它，别的用户下次再登记。 */
    @Test
    void publishFailureDoesNotThrow() {
        when(assetMapper.publishBytes(anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("db down"));

        service.publishBytes(ASSET_ID, "hash", OBJECT_REF, null, 100L);
    }

    private ContentAsset asset(String objectRef) {
        ContentAsset asset = new ContentAsset();
        asset.setId(ASSET_ID);
        asset.setContentHash("hash");
        asset.setObjectRef(objectRef);
        asset.setCoverRef("cover");
        asset.setFileSize(100L);
        asset.setStatus(ContentAssetStatus.READY);
        return asset;
    }

    private MediaFile media() {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(7L);
        media.setPlatform(VideoPlatform.BILIBILI);
        media.setResourceType("UGC_VIDEO");
        media.setExternalResourceId("BV1xx411c7mD");
        media.setExternalUnitId("30000001");
        return media;
    }

    private VideoSourceUnit unit() {
        return new VideoSourceUnit(VideoPlatform.BILIBILI, "UGC_VIDEO", "BV1xx411c7mD", "30000001",
                "https://www.bilibili.com/video/BV1xx411c7mD", "标题", "作者", 1000L, null, null);
    }
}
