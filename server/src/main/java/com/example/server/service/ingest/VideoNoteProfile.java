package com.example.server.service.ingest;

import com.example.server.dto.AnalysisMode;

/**
 * 系统默认视频笔记的固定任务身份。
 *
 * <p>默认笔记必须在媒体进入 {@code MEDIA_READY} 后自动生成，而现有 AI 主链按
 * {@code (mediaId, goalDigest(goal, mode))} 保存结果。因此目标文本和版本号集中定义在这里：
 * 导入模块只能通过 {@link VideoNoteTaskPort} 提交本身份，不能传入任意 Prompt。
 *
 * <p>修改目标语义必须升级 {@link #VERSION}，否则旧的 Checkpoint 会被错误复用。V2 的目标文本
 * 增加"有平台章节时按全部章节组织、每章提供范围内证据或明确证据不足"（计划 §6.2）——
 * 目标文本变化本身就让 V2 摘要与 V1 逐字节不同，任务去重、活跃键与 Checkpoint 自动隔离，
 * 不依赖消息字段的透传（D-077）。
 */
public final class VideoNoteProfile {

    /** 默认笔记的版本化身份，参与 {@code goalDigest} 并对用户可见。 */
    public static final String VERSION = "VIDEO_NOTE_V2";

    /** 历史版本标识：旧媒体上的 V1 结果继续可读，但不再被当作当前版本（D-078）。 */
    public static final String LEGACY_VERSION = "VIDEO_NOTE_V1";

    /** V2 固定目标文本；改动等价于换一个分析任务，必须同时升级版本。 */
    public static final String GOAL =
            "提取视频核心内容，生成结构化视频笔记；包含主题概述、关键观点、重要事实和时间戳证据。"
                    + "若视频存在平台章节，必须按全部章节顺序组织，并为每章提供落在该章时间范围内的证据，"
                    + "或明确声明该章未提取到可核验证据。";

    /** V1 目标文本（只读回退用）：懒升级期间 V1 结果仍可查询。 */
    public static final String LEGACY_GOAL =
            "提取视频核心内容，生成结构化视频笔记；包含主题概述、关键观点、重要事实和时间戳证据。";

    /** 默认笔记固定使用通用模式，不与用户交互模式混用结果。 */
    public static final AnalysisMode MODE = AnalysisMode.GENERAL;

    private VideoNoteProfile() {
    }
}
