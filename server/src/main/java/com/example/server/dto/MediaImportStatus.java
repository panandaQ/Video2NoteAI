package com.example.server.dto;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 导入子项与 URL 媒体共用的业务状态。
 *
 * <p>状态链把“媒体入库”和“固定默认笔记”串成一条面向用户的进度：
 * 媒体进入 {@link #MEDIA_READY} 只表示完整媒体已保存到 MinIO，只有默认笔记生成完成后才是
 * {@link #READY}。AI 内部阶段仍由 {@link TaskStage} 表达，两者不得混写。
 */
public enum MediaImportStatus {

    PENDING_DISPATCH,
    QUEUED,
    ACQUIRING,
    MEDIA_READY,
    ANALYSIS_QUEUED,
    ANALYZING,
    READY,
    DISPATCH_FAILED,
    FAILED,
    /** 旧本地上传记录的历史终态：只表示媒体文件可用，不等价于 {@link #READY}。 */
    COMPLETED;

    private static final Map<MediaImportStatus, Set<MediaImportStatus>> ALLOWED =
            new EnumMap<>(MediaImportStatus.class);

    static {
        ALLOWED.put(PENDING_DISPATCH, EnumSet.of(QUEUED, DISPATCH_FAILED, FAILED));
        ALLOWED.put(QUEUED, EnumSet.of(ACQUIRING, DISPATCH_FAILED, FAILED));
        // 可重试的获取异常把媒体从 ACQUIRING 放回 QUEUED，由有限重投或恢复扫描再次尝试。
        ALLOWED.put(ACQUIRING, EnumSet.of(MEDIA_READY, QUEUED, FAILED));
        ALLOWED.put(MEDIA_READY, EnumSet.of(ANALYSIS_QUEUED, FAILED));
        ALLOWED.put(ANALYSIS_QUEUED, EnumSet.of(ANALYZING, FAILED));
        // 默认笔记的可重试失败回到 ANALYSIS_QUEUED 等重投，不清空已完成的 Context/Chunk。
        ALLOWED.put(ANALYZING, EnumSet.of(READY, ANALYSIS_QUEUED, FAILED));
        ALLOWED.put(DISPATCH_FAILED, EnumSet.of(PENDING_DISPATCH, QUEUED));
        ALLOWED.put(FAILED, EnumSet.of(PENDING_DISPATCH));
        ALLOWED.put(READY, EnumSet.noneOf(MediaImportStatus.class));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(MediaImportStatus.class));
    }

    public boolean canTransitionTo(MediaImportStatus next) {
        return next != null && ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == COMPLETED;
    }

    /** 媒体是否仍需要一次 Unit 获取（下载 + 哈希 + MinIO 落盘）。 */
    public boolean needsAcquire() {
        return this == PENDING_DISPATCH || this == QUEUED || this == DISPATCH_FAILED;
    }

    /** 媒体入库是否已经完成：后续只允许默认笔记与用户目标分析，不得重复下载。 */
    public boolean isMediaStored() {
        return this == MEDIA_READY || this == ANALYSIS_QUEUED || this == ANALYZING
                || this == READY || this == COMPLETED;
    }

    /**
     * 媒体列表可见性：只有旧上传 {@link #COMPLETED} 与新 URL {@link #READY} 对用户可见。
     * 中间态和失败态不进入 `/media/list`。
     */
    public boolean isVisibleInMediaList() {
        return this == READY || this == COMPLETED;
    }
}
