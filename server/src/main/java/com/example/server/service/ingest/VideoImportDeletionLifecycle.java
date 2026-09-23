package com.example.server.service.ingest;

import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.VideoImportItem;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.service.MediaDeletionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 删除媒体时收敛导入子项（契约 §9.4 / AC-21）。
 *
 * <p>用户删除一个仍在处理中的媒体时，引用它的非终态子项不能再等待：媒体行马上就不存在了，
 * 它们永远不会再收到状态推进，父任务会永久停在处理中。这里在媒体行删除<b>之前</b>把它们条件更新为
 * {@code FAILED/MEDIA_DELETED}，并从子项重算受影响的父任务。
 *
 * <p>已经完成的子项不受影响：删除媒体是用户对"这个视频"的决定，历史导入任务里其他已完成的单元仍需保留。
 * 恢复扫描里有一条同样的兜底逻辑，用于处理绕过服务层的删除（例如人工 SQL），两条路径共用同一个错误码。
 */
@Component
public class VideoImportDeletionLifecycle implements MediaDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(VideoImportDeletionLifecycle.class);

    private final VideoImportItemMapper itemMapper;
    private final ImportJobAggregator aggregator;

    public VideoImportDeletionLifecycle(VideoImportItemMapper itemMapper,
                                        ImportJobAggregator aggregator) {
        this.itemMapper = itemMapper;
        this.aggregator = aggregator;
    }

    @Override
    public void beforeMediaDeleted(Long mediaId, Long userId) {
        if (mediaId == null) {
            return;
        }
        // 先取出关联任务再更新：更新之后这些子项已经是终态，仍需要重算它们各自的父任务。
        List<Long> affected = itemMapper.findByMediaId(mediaId).stream()
                .map(VideoImportItem::getImportId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        int changed = itemMapper.updatePendingToTerminalByMediaId(mediaId,
                MediaImportStatus.FAILED, false, VideoImportErrorCode.MEDIA_DELETED.name());
        if (changed == 0) {
            log.debug("video_import_delete_no_open_items mediaId={}", mediaId);
            return;
        }
        log.info("video_import_items_closed_by_media_delete mediaId={} userId={} items={} jobs={}",
                mediaId, userId, changed, affected.size());
        affected.forEach(aggregator::recompute);
    }
}
