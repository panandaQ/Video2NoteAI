package com.example.server.service.ingest;

import com.example.server.service.AnalysisDispatchService;

/**
 * 默认视频笔记的任务端口。
 *
 * <p>导入模块只能通过本端口提交系统固定的默认笔记目标，不能接收任意 Prompt：任务身份由
 * {@link VideoNoteProfile} 集中定义并版本化。实现必须复用现有分析分发链路（活跃键、消息结构、
 * Consumer、Checkpoint、死信与结果复用），不新建分析消息或 AI Consumer。
 */
public interface VideoNoteTaskPort {

    /**
     * 为一个已经进入 {@code MEDIA_READY} 的媒体提交默认视频笔记。
     *
     * @return 与现有分析分发一致的四种结果：受理、重复、限流、失败
     */
    AnalysisDispatchService.SubmissionResult submitDefaultNote(Long mediaId);
}
