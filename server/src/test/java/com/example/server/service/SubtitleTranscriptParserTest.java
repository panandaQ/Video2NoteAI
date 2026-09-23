package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.dto.TranscriptSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CC 字幕解析契约（计划 §4.5 / §7.1）：秒转毫秒、NaN/负值/越界、重叠区间并集、最大空洞、非法 JSON。
 */
class SubtitleTranscriptParserTest {

    private static final String WRAP = "{\"body\":[%s]}";

    private static String cue(double from, double to, String content) {
        return "{\"from\":" + from + ",\"to\":" + to + ",\"content\":\"" + content + "\"}";
    }

    @Test
    void parsesSecondsToMillisAndMergesOverlapsWithUnionCoverage() {
        String json = WRAP.formatted(String.join(",",
                cue(0, 10, "第一句"),
                cue(8, 20, "第二句"),
                cue(30, 40, "第三句")));
        SubtitleTranscriptParser.ParsedSubtitle parsed = SubtitleTranscriptParser.parse(json, 60_000L);

        assertEquals(2, parsed.segments().size(), "重叠区间合并为并集");
        assertEquals(TranscriptSource.CC, parsed.segments().get(0).source());
        assertEquals(0L, parsed.segments().get(0).startMs());
        assertEquals(20_000L, parsed.segments().get(0).endMs());
        assertEquals("第一句\n第二句", parsed.segments().get(0).text());
        assertEquals(0.5, parsed.coverage(), 1e-9, "(20+10)/60=0.5");
        assertEquals(10_000L, parsed.maxGapMs());
        assertEquals(0L, parsed.headOffsetMs());
        assertEquals(20_000L, parsed.tailOffsetMs());
    }

    @Test
    void fullCoverageAndZeroCoverageBoundaries() {
        assertEquals(1.0, SubtitleTranscriptParser.parse(
                WRAP.formatted(cue(0, 60, "a")), 60_000L).coverage(), 1e-9);
        assertEquals(0.01, SubtitleTranscriptParser.parse(
                WRAP.formatted(cue(0, 0.6, "a")), 60_000L).coverage(), 1e-9);
    }

    @Test
    void adjacentCuesDoNotCountAsGap() {
        SubtitleTranscriptParser.ParsedSubtitle parsed = SubtitleTranscriptParser.parse(
                WRAP.formatted(String.join(",", cue(0, 10, "a"), cue(10, 20, "b"))), 60_000L);
        assertEquals(1, parsed.segments().size(), "首尾相接且同窗口 → 并入并集");
        assertEquals(0L, parsed.maxGapMs());
    }

    /** 2026-09-20 media 61 缺陷回归：跨 60s 网格的相接 cue 必须拆段，防止长段被复制进每个切片。 */
    @Test
    void cuesAcrossGridBoundaryAreSplitIntoPerWindowSegments() {
        String json = WRAP.formatted(String.join(",",
                cue(50, 60, "第一句"), cue(60, 70, "第二句"), cue(70, 80, "第三句"),
                cue(120, 130, "第四句")));
        SubtitleTranscriptParser.ParsedSubtitle parsed = SubtitleTranscriptParser.parse(json, 300_000L);

        assertEquals(3, parsed.segments().size(), "每个 60s 窗口一段，不跨网格合并");
        assertEquals(50_000L, parsed.segments().get(0).startMs());
        assertEquals(60_000L, parsed.segments().get(0).endMs());
        assertEquals("第一句", parsed.segments().get(0).text());
        assertEquals(60_000L, parsed.segments().get(1).startMs());
        assertEquals(80_000L, parsed.segments().get(1).endMs());
        assertEquals("第二句\n第三句", parsed.segments().get(1).text());
        assertEquals(120_000L, parsed.segments().get(2).startMs());
        assertEquals("第四句", parsed.segments().get(2).text());
        // 覆盖率仍按区间并集计算，与拆段无关：(10+20+10)/300
        assertEquals(0.13333333333333333, parsed.coverage(), 1e-9);
    }

    @Test
    void invalidDataRejected() {
        assertThrows(IllegalStateException.class, () -> SubtitleTranscriptParser.parse("not-json", 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse("{\"body\":[]}", 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse(WRAP.formatted(cue(Double.NaN, 10, "a")), 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse(WRAP.formatted(cue(20, 10, "a")), 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse(WRAP.formatted(cue(-1, 10, "a")), 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse(WRAP.formatted(cue(0, 65, "a")), 60_000L));
        assertThrows(IllegalStateException.class, () ->
                SubtitleTranscriptParser.parse(WRAP.formatted(cue(0, 10, "  ")), 60_000L));
    }

    @Test
    void nullDurationSkipsBoundCheckAndCoverageIsOne() {
        SubtitleTranscriptParser.ParsedSubtitle parsed = SubtitleTranscriptParser.parse(
                WRAP.formatted(cue(0, 10, "a")), null);
        assertEquals(1.0, parsed.coverage(), 1e-9);
        assertEquals(1, parsed.segments().size());
    }
}
