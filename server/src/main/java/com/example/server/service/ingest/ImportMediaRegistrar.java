package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.source.VideoSourceUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 把解析结果幂等登记为媒体记录与导入子项。
 *
 * <p>整个登记过程在一个短事务内完成：契约要求“合集任一条目缺少稳定身份时整单失败，不写入部分子项”，
 * 因此身份校验必须在事务之前完成，事务内只做数据库写入。
 *
 * <p>并发与重复消息靠唯一约束收敛：媒体按四段身份复用，子项按 {@code (import_id, media_id)} 复用；
 * 唯一键冲突属于并发复用路径，不记为系统异常。
 */
@Service
public class ImportMediaRegistrar {

    private static final Logger log = LoggerFactory.getLogger(ImportMediaRegistrar.class);
    private static final int MAX_FILENAME_LENGTH = 255;

    private final MediaFileMapper mediaFileMapper;
    private final VideoImportItemMapper itemMapper;
    private final ContentAssetService contentAssetService;

    public ImportMediaRegistrar(MediaFileMapper mediaFileMapper,
                                VideoImportItemMapper itemMapper,
                                ContentAssetService contentAssetService) {
        this.mediaFileMapper = mediaFileMapper;
        this.itemMapper = itemMapper;
        this.contentAssetService = contentAssetService;
    }

    /** 一个 Unit 的登记结果，供投递阶段决定是否需要获取媒体。 */
    public record RegisteredUnit(Long mediaId,
                                 MediaImportStatus mediaStatus,
                                 boolean reused,
                                 int itemOrder) {
    }

    @Transactional
    public List<RegisteredUnit> register(VideoImportJob job, List<VideoSourceUnit> units) {
        List<RegisteredUnit> registered = new ArrayList<>(units.size());
        for (VideoSourceUnit unit : units) {
            MediaFile media = mediaFileMapper.findBySourceUnit(job.getUserId(),
                    unit.platform(), unit.resourceType(),
                    unit.externalResourceId(), unit.externalUnitId());
            boolean reused = media != null;
            if (media == null) {
                media = insertMedia(job.getUserId(), job.getRequestedQuality(), unit);
                if (media == null) {
                    // 并发插入：唯一约束赢了另一个请求，回查并复用它已建立的记录。
                    media = mediaFileMapper.findBySourceUnit(job.getUserId(),
                            unit.platform(), unit.resourceType(),
                            unit.externalResourceId(), unit.externalUnitId());
                    reused = true;
                }
            }
            if (media == null) {
                throw new IllegalStateException("媒体登记失败：唯一键竞争后未取到记录");
            }
            // 挂到共享内容资产：资产已有可用字节时，这里会直接把对象引用写进用户条目，
            // 获取阶段因此完全不下载（D-068 / AC-06）。
            contentAssetService.attach(media, unit);
            upsertItem(job.getId(), media, unit, reused);
            registered.add(new RegisteredUnit(media.getId(), media.getStatus(), reused, unit.itemOrder()));
        }
        log.info("video_import_units_registered importId={} units={} reused={}",
                job.getId(), registered.size(),
                registered.stream().filter(RegisteredUnit::reused).count());
        return registered;
    }

    private MediaFile insertMedia(Long userId, Integer requestedQuality, VideoSourceUnit unit) {
        MediaFile media = new MediaFile();
        media.setUserId(userId);
        media.setRequestedQuality(requestedQuality);
        media.setFilename(displayFilename(unit));
        // URL 媒体在 MEDIA_READY 之前没有对象地址，禁止写伪路径；来源身份必须写齐四段。
        media.setFilePath(null);
        media.setStatus(MediaImportStatus.PENDING_DISPATCH);
        media.setPlatform(unit.platform());
        media.setResourceType(unit.resourceType());
        media.setExternalResourceId(unit.externalResourceId());
        media.setExternalUnitId(unit.externalUnitId());
        media.setCanonicalUrl(unit.canonicalUrl());
        media.setSourceTitle(unit.title());
        media.setSourceAuthor(unit.author());
        media.setSourceDurationMs(unit.durationMs());
        // 平台封面地址只是抓取来源；真正的 cover_url 在获取阶段转存为受管对象后才写。
        media.setSourceCoverUrl(unit.coverUrl());
        media.setAcquireAttemptCount(0);
        media.setAcquireRetryable(false);
        media.setNoteAttemptCount(0);
        media.setNoteRetryable(false);
        try {
            mediaFileMapper.insert(media);
            return media;
        } catch (DuplicateKeyException e) {
            log.info("video_import_media_duplicate userId={} sourceKey={}", userId, unit.sourceKey());
            return null;
        }
    }

    private void upsertItem(Long importId, MediaFile media, VideoSourceUnit unit, boolean reused) {
        VideoImportItem existing = itemMapper.findOne(importId, media.getId());
        if (existing != null) {
            // 重复解析消息：关联已建立，保持现有子项状态，不重置进度。
            return;
        }
        VideoImportItem item = new VideoImportItem();
        item.setImportId(importId);
        item.setMediaId(media.getId());
        item.setItemOrder(unit.itemOrder() == null ? 1 : unit.itemOrder());
        item.setReused(reused);
        item.setItemStatus(media.getStatus());
        // 复用到的媒体可能停在 FAILED：只有"可重试失败"才把子项标成可重试，
        // 这样新的导入任务或 PARTIAL_SUCCESS 重试会真的再获取一次（D-053）。
        item.setRetryable(media.getStatus() == MediaImportStatus.FAILED
                && Boolean.TRUE.equals(media.getAcquireRetryable()));
        itemMapper.insert(item);
    }

    private String displayFilename(VideoSourceUnit unit) {
        String title = unit.title() == null ? "" : unit.title().trim();
        if (!title.isEmpty()) {
            return title.length() > MAX_FILENAME_LENGTH ? title.substring(0, MAX_FILENAME_LENGTH) : title;
        }
        return unit.platform().name() + "_" + unit.externalUnitId() + ".mp4";
    }
}
