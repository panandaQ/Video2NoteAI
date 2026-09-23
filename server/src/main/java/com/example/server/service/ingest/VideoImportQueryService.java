package com.example.server.service.ingest;

import com.example.server.dto.ContainerSummary;
import com.example.server.dto.ImportCounts;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.TaskEvent;
import com.example.server.dto.TaskStage;
import com.example.server.dto.TaskStatus;
import com.example.server.dto.VideoImportDetailResponse;
import com.example.server.dto.VideoImportItemResponse;
import com.example.server.dto.VideoImportJobStatus;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoImportItem;
import com.example.server.entity.VideoImportJob;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoImportItemMapper;
import com.example.server.mapper.VideoImportJobMapper;
import com.example.server.service.AgentCheckpointService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 导入任务查询。
 *
 * <p>所有查询都带 {@code user_id} 归属校验：任务不存在或不属于当前用户返回统一的资源不可用，
 * 不泄漏其他用户的导入信息。子项媒体状态使用批量查询，禁止逐条回表。
 *
 * <p>查询直查数据库，不设进度缓存：客户端的主流程改为订阅终态 SSE（D-066）之后，
 * "高频轮询同一个未推进的任务"这个唯一的缓存热点已经消失。Redis 仍服务于去重、锁与限流。
 */
@Service
public class VideoImportQueryService {

    private static final Logger log = LoggerFactory.getLogger(VideoImportQueryService.class);

    private final VideoImportJobMapper jobMapper;
    private final VideoImportItemMapper itemMapper;
    private final MediaFileMapper mediaFileMapper;
    private final AgentCheckpointService checkpointService;

    public VideoImportQueryService(VideoImportJobMapper jobMapper,
                                   VideoImportItemMapper itemMapper,
                                   MediaFileMapper mediaFileMapper,
                                   AgentCheckpointService checkpointService) {
        this.jobMapper = jobMapper;
        this.itemMapper = itemMapper;
        this.mediaFileMapper = mediaFileMapper;
        this.checkpointService = checkpointService;
    }

    /**
     * 任务详情。
     *
     * <p>直接读数据库：进度缓存已随"前端改订阅终态事件"一并移除（D-066）。它的价值只存在于
     * "客户端高频轮询同一个未推进的任务"这一种访问模式下，而完成通知改为 SSE 后该模式不再存在；
     * 保留它就等于为一个不存在的热点长期维护一套 Redis 键空间与 TTL 配置。
     *
     * @throws NoSuchElementException 任务不存在或不属于当前用户（HTTP 404）
     */
    public VideoImportDetailResponse detail(Long userId, Long importId) {
        return compose(requireOwned(userId, importId));
    }

    /**
     * 归属校验的唯一入口：不存在与越权不可区分，统一 404。
     *
     * @throws NoSuchElementException 任务不存在或不属于当前用户
     */
    public VideoImportJob requireOwned(Long userId, Long importId) {
        VideoImportJob job = jobMapper.findOwnedById(importId, userId);
        if (job == null) {
            throw new NoSuchElementException("导入任务不存在");
        }
        return job;
    }

    /**
     * SSE 订阅建立时的状态回放（D-066）。
     *
     * <p>对外只投影三种语义：{@code PROCESSING / COMPLETED / FAILED}。中间状态的存在意义是幂等、
     * CAS 与恢复位置，客户端不需要区分，因此这里不返回细粒度阶段（{@code stage} 为 {@code null}）。
     *
     * <p>每次订阅都重新读一次数据库：回放值就是当前事实，而不是注册订阅那一刻之前的旧值。
     */
    public TaskEvent currentEvent(Long userId, Long importId) {
        return project(requireOwned(userId, importId).getStatus());
    }

    private TaskEvent project(VideoImportJobStatus status) {
        if (status == VideoImportJobStatus.COMPLETED) {
            return TaskEvent.of(TaskStatus.of(TaskStatus.State.COMPLETED,
                    ImportTerminalEventPublisher.COMPLETED_MESSAGE), null);
        }
        if (status != null && status.isTerminal()) {
            // PARTIAL_SUCCESS 也是终态：多单元部分失败对客户端就是"有失败"，原因留在详情接口里查。
            String message = status == VideoImportJobStatus.PARTIAL_SUCCESS
                    ? ImportTerminalEventPublisher.PARTIAL_MESSAGE
                    : ImportTerminalEventPublisher.FAILED_MESSAGE;
            return TaskEvent.of(TaskStatus.of(TaskStatus.State.FAILED, message), null);
        }
        return TaskEvent.of(TaskStatus.of(TaskStatus.State.PROCESSING,
                ImportTerminalEventPublisher.PROCESSING_MESSAGE), null);
    }

    private VideoImportDetailResponse compose(VideoImportJob job) {
        Long importId = job.getId();
        List<VideoImportItem> items = itemMapper.findByImportId(importId);
        Map<Long, MediaFile> medias = loadMedias(items);

        List<VideoImportItemResponse> itemViews = items.stream()
                .map(item -> toItemView(item, medias.get(item.getMediaId())))
                .toList();

        return new VideoImportDetailResponse(
                job.getId(),
                job.getStatus(),
                job.getTargetType(),
                job.getPlatform() == null ? null : job.getPlatform().name(),
                new ContainerSummary(job.getContainerId(), job.getContainerTitle()),
                new ImportCounts(count(job.getTotalCount()), count(job.getReusedCount()),
                        count(job.getCompletedCount()), count(job.getFailedCount())),
                Boolean.TRUE.equals(job.getRetryable()),
                job.getErrorCode(),
                job.getErrorMessage(),
                itemViews,
                job.getCreatedAt(),
                job.getUpdatedAt());
    }

    private Map<Long, MediaFile> loadMedias(List<VideoImportItem> items) {
        List<Long> mediaIds = items.stream().map(VideoImportItem::getMediaId).distinct().toList();
        if (mediaIds.isEmpty()) {
            return Map.of();
        }
        return mediaFileMapper.selectBatchIds(mediaIds).stream()
                .collect(Collectors.toMap(MediaFile::getId, Function.identity(), (first, second) -> first));
    }

    private VideoImportItemResponse toItemView(VideoImportItem item, MediaFile media) {
        // 进度看媒体行，不看子项：子项只持久化 PENDING_* → READY/FAILED（D-066），
        // 它是计数事实来源与关联关系，不再是进度载体。媒体行缺失（已删除）时退回子项终态。
        MediaImportStatus progress = media == null ? item.getItemStatus() : media.getStatus();
        return new VideoImportItemResponse(
                item.getMediaId(),
                item.getItemOrder() == null ? 0 : item.getItemOrder(),
                Boolean.TRUE.equals(item.getReused()),
                media == null ? null : media.getSourceTitle(),
                media == null ? null : media.getCanonicalUrl(),
                progress,
                projectStage(progress, item.getMediaId()),
                Boolean.TRUE.equals(item.getRetryable()),
                item.getErrorCode());
    }

    /**
     * 业务状态到可空 {@code stage} 的投影（契约 §3.2 / D-033）。
     *
     * <p>分析开始前必须返回 {@code null}：不允许为了让字段有值而伪造 {@code CONTEXT_COMPLETED}。
     * {@code ANALYZING} 阶段读取默认目标 Checkpoint，尚未落下记录的瞬时窗口回退 {@code CONSUMING}。
     */
    private TaskStage projectStage(MediaImportStatus status, Long mediaId) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case PENDING_DISPATCH, QUEUED, ACQUIRING, MEDIA_READY -> null;
            case ANALYSIS_QUEUED -> TaskStage.QUEUED;
            case ANALYZING -> {
                TaskStage stage = loadNoteStage(mediaId);
                yield stage == null ? TaskStage.CONSUMING : stage;
            }
            case READY -> TaskStage.COMPLETED;
            case DISPATCH_FAILED, FAILED -> loadNoteStage(mediaId);
            // 旧上传记录只表示媒体文件可用，没有默认笔记，因此没有阶段可投影。
            case COMPLETED -> null;
        };
    }

    private TaskStage loadNoteStage(Long mediaId) {
        try {
            return checkpointService.loadStage(mediaId, VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
        } catch (RuntimeException e) {
            log.warn("video_import_stage_projection_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    private int count(Integer value) {
        return value == null ? 0 : value;
    }
}
