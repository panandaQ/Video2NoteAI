package com.example.server.service.ingest;

import com.example.server.dto.ContentAssetStatus;
import com.example.server.entity.ContentAsset;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.ContentAssetMapper;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.source.VideoSourceUnit;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 共享内容资产的登记与读取（D-068）。
 *
 * <p>它是"内容级"与"用户级"之间的唯一转换点：
 * <ul>
 *   <li>用户条目（{@code media_files}）负责归属、权限、列表与删除；</li>
 *   <li>内容资产（{@code content_assets}）负责字节与元数据的共享身份。</li>
 * </ul>
 *
 * <p>复用判定不依赖 Redis 或内存状态，只看数据库事实：资产上有 {@code object_ref} 且对象真的存在，
 * 就把引用挂到用户条目上——用户在获取阶段因此完全不需要下载（AC-06）。对象被人工删除时，
 * 引用检查会失败并退回正常下载路径，不会出现"库里指向不存在的对象"。
 */
@Service
public class ContentAssetService {

    private static final Logger log = LoggerFactory.getLogger(ContentAssetService.class);

    private final ContentAssetMapper assetMapper;
    private final MediaFileMapper mediaFileMapper;
    private final MinioUtils minioUtils;
    private final ContentAssetReadyNotifier readyNotifier;

    public ContentAssetService(ContentAssetMapper assetMapper,
                               MediaFileMapper mediaFileMapper,
                               MinioUtils minioUtils,
                               ContentAssetReadyNotifier readyNotifier) {
        this.assetMapper = assetMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.minioUtils = minioUtils;
        this.readyNotifier = readyNotifier;
    }

    /**
     * 把用户条目挂到内容资产上；资产已有可用字节时顺便把对象引用写进用户条目。
     *
     * <p>写入用户条目这一步是"跨用户复用字节"的落点：媒体行一旦带上 {@code file_path}，
     * 获取链路就会按"对象已在存储里"直接推进到 {@code MEDIA_READY}，一次下载都不发。
     *
     * @return 该单元对应的内容资产（永不返回 {@code null}）
     */
    public ContentAsset attach(MediaFile media, VideoSourceUnit unit) {
        ContentAsset asset = findOrCreate(unit);
        if (asset == null) {
            throw new IllegalStateException("内容资产登记失败：唯一键竞争后未取到记录");
        }
        if (media.getContentAssetId() == null) {
            mediaFileMapper.bindContentAsset(media.getId(), asset.getId());
            media.setContentAssetId(asset.getId());
        }
        adoptSharedBytes(media, asset);
        return asset;
    }

    /**
     * 命中共享字节时把对象引用挂到用户条目上。
     *
     * <p>把"对象是否存在"也纳入判定：数据库里的引用可能与对象存储不一致（人工删除、桶迁移），
     * 此时必须让用户条目退回可下载状态，而不是让后续步骤读一个 404 的对象。
     *
     * @return 是否命中并写入了共享字节
     */
    public boolean adoptSharedBytes(MediaFile media, ContentAsset asset) {
        if (media.getFilePath() != null && !media.getFilePath().isBlank()) {
            return false;
        }
        if (asset == null || asset.getObjectRef() == null || asset.getObjectRef().isBlank()) {
            return false;
        }
        boolean present;
        try {
            present = minioUtils.objectExists(asset.getObjectRef());
        } catch (RuntimeException e) {
            log.warn("content_asset_object_check_failed assetId={} mediaId={}",
                    asset.getId(), media.getId(), e);
            return false;
        }
        if (!present) {
            log.warn("content_asset_object_missing assetId={} mediaId={} objectRef={}",
                    asset.getId(), media.getId(), asset.getObjectRef());
            return false;
        }
        int updated = mediaFileMapper.adoptSharedBytes(media.getId(), asset.getId(), asset.getObjectRef(),
                asset.getContentHash(), asset.getFileSize(), asset.getCoverRef());
        if (updated == 0) {
            return false;
        }
        media.setContentAssetId(asset.getId());
        media.setFilePath(asset.getObjectRef());
        media.setContentHash(asset.getContentHash());
        media.setFileSize(asset.getFileSize());
        if (asset.getCoverRef() != null) {
            media.setCoverUrl(asset.getCoverRef());
        }
        log.info("content_asset_bytes_reused assetId={} mediaId={} userId={}",
                asset.getId(), media.getId(), media.getUserId());
        return true;
    }

    /** 本轮下载完成后登记字节版本；已有版本时不覆盖（首个写入者胜出）。 */
    public void publishBytes(Long assetId, String contentHash, String objectRef,
                            String coverRef, long fileSize) {
        if (assetId == null || objectRef == null || objectRef.isBlank()) {
            return;
        }
        try {
            int written = assetMapper.publishBytes(assetId, contentHash, objectRef, coverRef, fileSize);
            if (written == 0) {
                assetMapper.publishCover(assetId, coverRef);
                log.info("content_asset_bytes_already_published assetId={} objectRef={}", assetId, objectRef);
                return;
            }
            log.info("content_asset_bytes_published assetId={} contentHash={} bytes={}",
                    assetId, contentHash, fileSize);
            // 只在"这次真的由我发布"时广播：字节已经是别人发布的，等待者早被那次广播唤醒过。
            readyNotifier.publish(assetId);
        } catch (RuntimeException e) {
            // 登记失败不影响本次导入：字节已经落盘、用户条目的 file_path 已经指向它，
            // 只是别的用户暂时还要自己下载一遍。恢复扫描或下一次导入会再次登记。
            log.warn("content_asset_publish_failed assetId={} objectRef={}", assetId, objectRef, e);
        }
    }

    /** 按用户条目的资产引用读资产；未绑定（旧数据）时回退按四段身份查。 */
    public ContentAsset findByMedia(MediaFile media) {
        if (media.getContentAssetId() != null) {
            ContentAsset asset = assetMapper.selectById(media.getContentAssetId());
            if (asset != null) {
                return asset;
            }
        }
        if (media.getPlatform() == null || media.getResourceType() == null
                || media.getExternalResourceId() == null || media.getExternalUnitId() == null) {
            return null;
        }
        return assetMapper.findBySourceUnit(media.getPlatform().name(), media.getResourceType(),
                media.getExternalResourceId(), media.getExternalUnitId());
    }

    private ContentAsset findOrCreate(VideoSourceUnit unit) {
        ContentAsset existing = assetMapper.findBySourceUnit(unit.platform().name(), unit.resourceType(),
                unit.externalResourceId(), unit.externalUnitId());
        if (existing != null) {
            return existing;
        }
        ContentAsset asset = new ContentAsset();
        asset.setPlatform(unit.platform());
        asset.setResourceType(unit.resourceType());
        asset.setExternalResourceId(unit.externalResourceId());
        asset.setExternalUnitId(unit.externalUnitId());
        asset.setCanonicalUrl(unit.canonicalUrl());
        asset.setTitle(unit.title());
        asset.setAuthor(unit.author());
        asset.setDurationMs(unit.durationMs());
        asset.setStatus(ContentAssetStatus.PROCESSING);
        try {
            assetMapper.insert(asset);
            return asset;
        } catch (DuplicateKeyException e) {
            // 并发登记：唯一约束赢了另一端，回查复用它建立的行。
            log.info("content_asset_duplicate sourceKey={}", unit.sourceKey());
            return assetMapper.findBySourceUnit(unit.platform().name(), unit.resourceType(),
                    unit.externalResourceId(), unit.externalUnitId());
        }
    }
}
