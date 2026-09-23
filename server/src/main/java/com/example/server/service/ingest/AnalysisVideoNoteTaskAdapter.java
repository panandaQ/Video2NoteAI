package com.example.server.service.ingest;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AnalysisDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 默认视频笔记端口的适配实现：把固定任务身份交给现有分析分发链路。
 *
 * <p>只做两件事：按 {@code mediaId} 读回媒体、用 {@link VideoNoteProfile} 的固定目标与模式调用
 * {@link AnalysisDispatchService#submitBackground}。不新建分析消息、不新建 Consumer、不复制活跃键或限流逻辑。
 *
 * <p>必须走后台入口：默认笔记是系统自动任务，不能用用户交互的 AI 配额（5 次/分钟），
 * 否则多单元合集从第 6 个单元起就只能靠恢复扫描逐轮补投（契约 §7.4 / D-049）。
 */
@Component
public class AnalysisVideoNoteTaskAdapter implements VideoNoteTaskPort {

    private static final Logger log = LoggerFactory.getLogger(AnalysisVideoNoteTaskAdapter.class);

    private final MediaFileMapper mediaFileMapper;
    private final AnalysisDispatchService dispatchService;

    public AnalysisVideoNoteTaskAdapter(MediaFileMapper mediaFileMapper,
                                        AnalysisDispatchService dispatchService) {
        this.mediaFileMapper = mediaFileMapper;
        this.dispatchService = dispatchService;
    }

    @Override
    public AnalysisDispatchService.SubmissionResult submitDefaultNote(Long mediaId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            log.warn("video_note_submit_media_missing mediaId={}", mediaId);
            return AnalysisDispatchService.SubmissionResult.FAILED;
        }
        AnalysisDispatchService.SubmissionResult result = dispatchService.submitBackground(
                media, VideoNoteProfile.GOAL, null, VideoNoteProfile.MODE);
        log.info("video_note_submitted mediaId={} profileVersion={} result={}",
                mediaId, VideoNoteProfile.VERSION, result);
        return result;
    }
}
