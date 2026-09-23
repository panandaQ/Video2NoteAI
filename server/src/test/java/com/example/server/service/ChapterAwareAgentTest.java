package com.example.server.service;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 章节强校验契约（计划 §5.3/§5.4/§7.1，交付约束 4）：缺章、乱序、章节证据越界或把无证据
 * 章节写成事实均不得通过；无证据章节必须明确写"未提取到可核验证据"。
 */
class ChapterAwareAgentTest {

    private static final List<VideoChapter> CHAPTERS = List.of(
            new VideoChapter("vp-0-0", "开场", 0, 20_000, 1, "BILIBILI_VIEW_POINT"),
            new VideoChapter("vp-1-20000", "正片", 20_000, 60_000, 1, "BILIBILI_VIEW_POINT"));

    private static AgentState.CriticResult passed() {
        return new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of());
    }

    private static VideoContext contextWithChapters() {
        return new VideoContext("http://minio/source.mp4", "goal", List.of(),
                60_000L, VideoContext.ANALYSIS_VERSION_V2, CHAPTERS);
    }

    private static AnalysisResult.Section section(String key, List<String> items) {
        return new AnalysisResult.Section(key, key, items);
    }

    private static AnalysisResult result(List<AnalysisResult.Section> sections,
                                         List<AnalysisResult.Evidence> evidence) {
        return new AnalysisResult("标题", List.of("结论"), evidence, List.of(), sections);
    }

    @Test
    void orderedSectionsWithInRangeEvidencePass() {
        AnalysisResult result = result(
                List.of(section("chapter:vp-0-0", List.of("开场要点")),
                        section("chapter:vp-1-20000", List.of("正片要点"))),
                List.of(new AnalysisResult.Evidence(10_000, "CC", "证据", "结论"),
                        new AnalysisResult.Evidence(30_000, "CC+OCR", "证据", "结论")));
        assertTrue(AgentLoopService.enforceChapterSections(
                contextWithChapters(), result, passed()).passed());
    }

    @Test
    void missingOrMisorderedSectionsRejected() {
        AnalysisResult missing = result(
                List.of(section("chapter:vp-1-20000", List.of("正片要点"))),
                List.of(new AnalysisResult.Evidence(30_000, "CC", "证据", "结论")));
        assertFalse(AgentLoopService.enforceChapterSections(
                contextWithChapters(), missing, passed()).passed());

        AnalysisResult misordered = result(
                List.of(section("chapter:vp-1-20000", List.of("正片要点")),
                        section("chapter:vp-0-0", List.of("开场要点"))),
                List.of(new AnalysisResult.Evidence(10_000, "CC", "证据", "结论")));
        assertFalse(AgentLoopService.enforceChapterSections(
                contextWithChapters(), misordered, passed()).passed());
    }

    @Test
    void extraSectionsRejected() {
        AnalysisResult result = result(
                List.of(section("chapter:vp-0-0", List.of("开场")),
                        section("chapter:vp-1-20000", List.of("正片")),
                        section("chapter:fake", List.of("多余"))),
                List.of(new AnalysisResult.Evidence(10_000, "CC", "证据", "结论")));
        assertFalse(AgentLoopService.enforceChapterSections(
                contextWithChapters(), result, passed()).passed());
    }

    @Test
    void chapterWithoutInRangeEvidenceNeedsExplicitAbsenceDeclaration() {
        AnalysisResult fabricated = result(
                List.of(section("chapter:vp-0-0", List.of("开场要点")),
                        section("chapter:vp-1-20000", List.of("正片讲了某件事"))),
                List.of(new AnalysisResult.Evidence(10_000, "CC", "证据", "结论")));
        assertFalse(AgentLoopService.enforceChapterSections(
                contextWithChapters(), fabricated, passed()).passed(),
                "正片没有章内证据却写了事实结论");

        AnalysisResult honest = result(
                List.of(section("chapter:vp-0-0", List.of("开场要点")),
                        section("chapter:vp-1-20000", List.of("未提取到可核验证据"))),
                List.of(new AnalysisResult.Evidence(10_000, "CC", "证据", "结论")));
        assertTrue(AgentLoopService.enforceChapterSections(
                contextWithChapters(), honest, passed()).passed());
    }

    @Test
    void noChaptersIsANoOp() {
        VideoContext context = new VideoContext("http://minio/source.mp4", "goal", List.of());
        AnalysisResult result = result(List.of(), List.of());
        AgentState.CriticResult critique = AgentLoopService.enforceChapterSections(
                context, result, passed());
        assertTrue(critique.passed());
        assertTrue(critique.feedback().isEmpty());
    }
}
