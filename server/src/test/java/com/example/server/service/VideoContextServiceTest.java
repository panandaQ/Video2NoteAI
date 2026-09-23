package com.example.server.service;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.AnalysisInputManifest;
import com.example.server.dto.TranscriptSegment;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Context 字幕优先与章节边界契约（计划 §4.5 / §5.1 / §7.1）。
 */
class VideoContextServiceTest {

    private final VideoImportProperties properties = new VideoImportProperties();

    private static TranscriptSegment cc(long startMs, long endMs, String text) {
        return new TranscriptSegment(startMs, endMs, text, TranscriptSource.CC);
    }

    private static TranscriptSegment asr(long startMs, long endMs, String text) {
        return new TranscriptSegment(startMs, endMs, text, TranscriptSource.ASR);
    }

    @Test
    void subtitleGateUsesUnionCoverageAndMaxGap() {
        SubtitleTranscriptParser.ParsedSubtitle ok = new SubtitleTranscriptParser.ParsedSubtitle(
                List.of(), 0.6, 0, 0, 0);
        SubtitleTranscriptParser.ParsedSubtitle lowCoverage = new SubtitleTranscriptParser.ParsedSubtitle(
                List.of(), 0.59, 0, 0, 0);
        SubtitleTranscriptParser.ParsedSubtitle bigGap = new SubtitleTranscriptParser.ParsedSubtitle(
                List.of(), 1.0, 181_000L, 0, 0);
        assertTrue(VideoContextService.subtitleGatePassed(ok, properties));
        assertFalse(VideoContextService.subtitleGatePassed(lowCoverage, properties));
        assertFalse(VideoContextService.subtitleGatePassed(bigGap, properties));
    }

    @Test
    void chapterBoundaryInsideWindowSplitsSegment() {
        List<VideoChapter> chapters = List.of(
                new VideoChapter("vp-0-0", "开场", 0, 20_000, 1, "BILIBILI_VIEW_POINT"),
                new VideoChapter("vp-1-20000", "正片", 20_000, 60_000, 1, "BILIBILI_VIEW_POINT"));
        List<VideoContext.VideoSegment> segments = VideoContextService.merge(
                List.of(cc(5_000, 55_000, "跨章文本")),
                List.of(),
                chapters,
                60_000L);

        assertEquals(2, segments.size(), "60 秒窗口被章节边界拆成两段");
        assertEquals(0L, segments.get(0).startMs());
        assertEquals(20_000L, segments.get(0).endMs());
        assertEquals("vp-0-0", segments.get(0).chapterId());
        assertEquals(20_000L, segments.get(1).startMs());
        assertEquals(60_000L, segments.get(1).endMs());
        assertEquals("vp-1-20000", segments.get(1).chapterId());
        assertEquals(TranscriptSource.CC, segments.get(1).source());
        assertEquals("跨章文本", segments.get(0).transcript(), "跨边界 cue 进入两侧切片");
    }

    @Test
    void unassignedHeadAndTailMaterialGetsNullChapter() {
        List<VideoChapter> chapters = List.of(
                new VideoChapter("vp-0-10000", "正片", 10_000, 30_000, 1, "BILIBILI_VIEW_POINT"));
        List<VideoContext.VideoSegment> segments = VideoContextService.merge(
                List.of(asr(0, 5_000, "片头"), asr(10_000, 20_000, "正片"), asr(30_000, 35_000, "片尾")),
                List.of(),
                chapters,
                60_000L);
        assertEquals(3, segments.size());
        assertEquals(null, segments.get(0).chapterId());
        assertEquals("vp-0-10000", segments.get(1).chapterId());
        assertEquals(null, segments.get(2).chapterId());
        assertEquals(TranscriptSource.ASR, segments.get(1).source());
    }

    @Test
    void ocrOnlySliceDefaultsToAsrSource() {
        // 无转录只有 OCR 的切片：source 缺省 ASR（toHit 侧只输出 "OCR"，不误标 ASR 转录）
        List<VideoContext.VideoSegment> segments = VideoContextService.merge(
                List.of(),
                List.of(new VideoContextService.FramePart(30_000L, "画面文字", "frame.jpg")),
                List.of(),
                60_000L);
        assertEquals(1, segments.size());
        assertEquals(TranscriptSource.ASR, segments.get(0).source());
        assertEquals(List.of("画面文字"), segments.get(0).ocrTexts());
    }

    @Test
    void presentChaptersWithMissingObjectFailRetryably() {
        MinioUtils minioUtils = mock(MinioUtils.class);
        when(minioUtils.readObjectBytes("video-import/content/h/analysis-input/v2/chapters.json"))
                .thenThrow(new IllegalStateException("object gone"));
        VideoContextService service = new VideoContextService(
                mock(SegmentedTranscriptionService.class), mock(com.example.server.utils.OcrUtils.class),
                minioUtils, mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(AgentTelemetry.class), properties, new ObjectMapper());

        AnalysisInputManifest manifest = new AnalysisInputManifest(1, "V2", "BILIBILI", "UGC_VIDEO",
                "BV1", "cid", "h", "OK", "AVAILABLE", "zh-Hans", "0",
                AnalysisInputManifest.CHAPTER_PRESENT, 2, 0L,
                "video-import/content/h/analysis-input/v2/subtitle-zh.json",
                "video-import/content/h/analysis-input/v2/chapters.json");
        assertThrows(IllegalStateException.class, () -> service.loadChapters(manifest));
    }

    @Test
    void absentOrInvalidChaptersReturnEmptyWithoutFailure() {
        MinioUtils minioUtils = mock(MinioUtils.class);
        VideoContextService service = new VideoContextService(
                mock(SegmentedTranscriptionService.class), mock(com.example.server.utils.OcrUtils.class),
                minioUtils, mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(AgentTelemetry.class), properties, new ObjectMapper());
        AnalysisInputManifest absent = new AnalysisInputManifest(1, "V2", "BILIBILI", "UGC_VIDEO",
                "BV1", "cid", "h", "OK", "ABSENT", null, null,
                AnalysisInputManifest.CHAPTER_ABSENT, 0, 0L, "s", "c");
        assertEquals(List.of(), service.loadChapters(absent));
        assertTrue(service.loadChapters(null).isEmpty());
    }

    @Test
    void needLoginSubtitleFailsWithoutAsrFallback() throws Exception {
        VideoContextService service = new VideoContextService(
                mock(SegmentedTranscriptionService.class), mock(com.example.server.utils.OcrUtils.class),
                mock(MinioUtils.class), mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class),
                mock(AgentTelemetry.class), properties, new ObjectMapper());
        AnalysisInputManifest needLogin = new AnalysisInputManifest(1, "V2", "BILIBILI", "UGC_VIDEO",
                "BV1", "cid", "h", "OK", AnalysisInputManifest.SUBTITLE_NEED_LOGIN, null, null,
                AnalysisInputManifest.CHAPTER_ABSENT, 0, 0L, "s", "c");
        java.lang.reflect.Method resolve = VideoContextService.class.getDeclaredMethod("resolveTranscripts",
                String.class, java.nio.file.Path.class, String.class,
                AnalysisInputManifest.class, Long.class, java.util.List.class);
        resolve.setAccessible(true);
        java.lang.reflect.InvocationTargetException e = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> resolve.invoke(service, "video.mp4", null, null, needLogin, 60_000L, List.of()));
        assertTrue(e.getCause() instanceof SubtitleLoginRequiredException,
                "登录态失效必须抛 SubtitleLoginRequiredException，而不是降级 ASR");
    }
}
