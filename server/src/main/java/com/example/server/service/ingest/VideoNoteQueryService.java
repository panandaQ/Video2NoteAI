package com.example.server.service.ingest;

import com.example.server.dto.AgentState;
import com.example.server.dto.TaskStage;
import com.example.server.dto.VideoNoteResponse;
import com.example.server.entity.MediaFile;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.MediaService;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * 默认视频笔记查询。
 *
 * <p>结果真源是现有 {@code agent_checkpoints}，不新增结果表：{@code media_files.ai_summary} 只是
 * 列表用的 Markdown 投影。任务未完成时返回当前业务状态与阶段，{@code note=null}。
 *
 * <p>媒体不存在或不属于当前用户统一返回 404，避免通过状态码区分“存在但无权限”。
 */
@Service
public class VideoNoteQueryService {

    private final MediaService mediaService;
    private final AgentCheckpointService checkpointService;

    public VideoNoteQueryService(MediaService mediaService, AgentCheckpointService checkpointService) {
        this.mediaService = mediaService;
        this.checkpointService = checkpointService;
    }

    public VideoNoteResponse note(Long userId, Long mediaId) {
        MediaFile media = requireOwned(userId, mediaId);
        AgentState state = checkpointService.loadResult(
                mediaId, VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
        TaskStage stage = checkpointService.loadStage(
                mediaId, VideoNoteProfile.GOAL, VideoNoteProfile.MODE);
        String profileVersion = media.getNoteProfileVersion();
        if (state == null && VideoNoteProfile.LEGACY_VERSION.equals(profileVersion)) {
            // D-078 懒升级期间：V2 未完成时返回 V1 结果并明确标注版本，不把 V1 冒充 V2
            state = checkpointService.loadResult(
                    mediaId, VideoNoteProfile.LEGACY_GOAL, VideoNoteProfile.MODE);
            if (state != null && state.result() != null) {
                stage = TaskStage.COMPLETED;
            }
        }
        return new VideoNoteResponse(
                mediaId,
                media.getStatus(),
                stage,
                profileVersion,
                state == null ? null : state.result(),
                Boolean.TRUE.equals(media.getNoteRetryable()),
                media.getNoteErrorCode());
    }

    private MediaFile requireOwned(Long userId, Long mediaId) {
        try {
            return mediaService.requireOwnedMedia(mediaId, userId);
        } catch (SecurityException e) {
            // 统一成 404：不能让调用方通过状态码判断媒体是否存在。
            throw new NoSuchElementException("媒体不存在");
        }
    }
}
