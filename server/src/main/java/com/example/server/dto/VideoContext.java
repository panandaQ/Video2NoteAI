package com.example.server.dto;

import java.util.List;

/**
 * 将视频中的语音和画面信息按时间轴整理成 Agent 可消费的统一上下文。
 *
 * <p>V2 扩展（计划 §4.5/§5.1）：{@code durationMs}、{@code analysisVersion} 与 {@code chapters}
 * 随上下文持久化；{@code VideoSegment} 显式携带转录来源与章节归属。旧检查点 JSON 缺省这些字段时
 * 反序列化为空值/ASR，逐字节兼容 V1 数据。
 */
public record VideoContext(
        String source,
        String userGoal,
        List<VideoSegment> segments,
        Long durationMs,
        String analysisVersion,
        List<VideoChapter> chapters
) {

    /** 内容处理版本：参与 Checkpoint Key、Qdrant payload 与任务身份（计划 §6.2）。 */
    public static final String ANALYSIS_VERSION_V2 = "VIDEO_CONTEXT_V2";

    public VideoContext {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("video source is required");
        userGoal = userGoal == null ? "" : userGoal.trim();
        segments = segments == null ? List.of() : List.copyOf(segments);
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
    }

    /** 兼容旧构造与旧调用方：durationMs/analysisVersion/chapters 缺省为空。 */
    public VideoContext(String source, String userGoal, List<VideoSegment> segments) {
        this(source, userGoal, segments, null, null, List.of());
    }

    public record VideoSegment(
            long startMs,
            long endMs,
            String transcript,
            List<String> ocrTexts,
            List<String> evidenceFrames,
            TranscriptSource source,
            String chapterId
    ) {
        public VideoSegment {
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("invalid segment range");
            transcript = transcript == null ? "" : transcript.trim();
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
            evidenceFrames = evidenceFrames == null ? List.of() : List.copyOf(evidenceFrames);
            source = source == null ? TranscriptSource.ASR : source;
            chapterId = chapterId == null || chapterId.isBlank() ? null : chapterId.trim();
        }

        /** 兼容旧构造：旧 JSON 缺省 source 按 ASR，无章节归属。 */
        public VideoSegment(long startMs, long endMs, String transcript,
                            List<String> ocrTexts, List<String> evidenceFrames) {
            this(startMs, endMs, transcript, ocrTexts, evidenceFrames, TranscriptSource.ASR, null);
        }
    }

    public String transcriptText() {
        return segments.stream()
                .map(VideoSegment::transcript)
                .filter(text -> !text.isBlank())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
