package com.example.server.service;

import com.example.server.dto.TranscriptSegment;
import com.example.server.dto.TranscriptSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * B 站 CC 字幕 JSON 解析与质量度量（计划 §4.5）：秒转毫秒、有限数值校验、
 * 重叠区间合并（区间并集）、覆盖率、最大无字幕空洞与首尾偏移。
 *
 * <p>质量门槛在 Context 构建层执行：覆盖率（并集）≥ {@code subtitle-min-coverage} 且
 * 最大空洞 ≤ {@code subtitle-max-gap-seconds} 才使用字幕，否则回退 ASR 并计
 * {@code subtitleCoverageRejected}。
 */
public final class SubtitleTranscriptParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /**
     * 合并段的网格窗口（与 Context 的 60 秒聚合窗口对齐）。
     *
     * <p>B 站 AI 字幕的 cue 常首尾相接（上一句 to == 下一句 from），若只按"重叠或相接"合并，
     * 会把整集合并成十几分钟长的段；随后 Context 合并层把这种长段复制进每个跨越的切片，
     * 造成上下文大面积重复（2026-09-20 media 61 实测：连续 10 个切片 transcript 相同）。
     * 因此合并范围限制在同一 60 秒网格窗口内：段长 ≤ 一个窗口，最多在窗口/章节边界两侧
     * 出现一次复制（这正是"章节边界拆段"要求的语义）。
     */
    private static final long GRID_MS = 60_000L;

    private SubtitleTranscriptParser() {
    }

    /**
     * @param durationMs 视频时长；为 {@code null} 时不做越界与覆盖率分母校验（覆盖率按 1 处理）
     * @throws IllegalStateException JSON 非法、body 缺失/为空、时间非法或越界
     */
    public static ParsedSubtitle parse(String json, Long durationMs) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("字幕 JSON 无法解析", e);
        }
        JsonNode items = root == null ? null : root.path("body");
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("字幕缺少非空 body 数组");
        }

        List<Cue> cues = new ArrayList<>();
        for (JsonNode item : items) {
            double fromSec = item.path("from").asDouble(Double.NaN);
            double toSec = item.path("to").asDouble(Double.NaN);
            String content = item.path("content").asText("");
            if (!Double.isFinite(fromSec) || !Double.isFinite(toSec)) {
                throw new IllegalStateException("字幕时间不是有限数值");
            }
            if (fromSec < 0 || toSec <= fromSec) {
                throw new IllegalStateException("字幕时间范围非法");
            }
            long startMs = Math.round(fromSec * 1000.0);
            long endMs = Math.round(toSec * 1000.0);
            if (durationMs != null && endMs > durationMs) {
                throw new IllegalStateException("字幕时间越界");
            }
            if (content.isBlank()) {
                throw new IllegalStateException("字幕正文为空");
            }
            cues.add(new Cue(startMs, endMs, content.trim()));
        }
        cues.sort(Comparator.comparingLong(Cue::startMs));

        List<TranscriptSegment> merged = new ArrayList<>();
        long coverageMs = 0;
        long maxGapMs = 0;
        long headOffsetMs = cues.get(0).startMs();
        long currentStart = -1;
        long currentEnd = -1;
        StringBuilder text = new StringBuilder();
        for (Cue cue : cues) {
            if (currentStart < 0) {
                currentStart = cue.startMs();
                currentEnd = cue.endMs();
                text.append(cue.content());
                continue;
            }
            boolean sameGrid = cue.startMs() / GRID_MS == currentStart / GRID_MS;
            if (cue.startMs() <= currentEnd && sameGrid) {
                // 同一 60s 窗口内的重叠/相接：并入并集，不重复计覆盖
                currentEnd = Math.max(currentEnd, cue.endMs());
                text.append('\n').append(cue.content());
            } else {
                long gap = cue.startMs() - currentEnd;
                maxGapMs = Math.max(maxGapMs, gap);
                coverageMs += currentEnd - currentStart;
                merged.add(new TranscriptSegment(currentStart, currentEnd, text.toString(), TranscriptSource.CC));
                currentStart = cue.startMs();
                currentEnd = cue.endMs();
                text = new StringBuilder(cue.content());
            }
        }
        coverageMs += currentEnd - currentStart;
        merged.add(new TranscriptSegment(currentStart, currentEnd, text.toString(), TranscriptSource.CC));

        long tailOffsetMs = durationMs == null ? 0 : Math.max(0, durationMs - currentEnd);
        double coverage = durationMs == null ? 1.0 : (double) coverageMs / durationMs;
        return new ParsedSubtitle(List.copyOf(merged), coverage, maxGapMs, headOffsetMs, tailOffsetMs);
    }

    private record Cue(long startMs, long endMs, String content) {
    }

    public record ParsedSubtitle(
            List<TranscriptSegment> segments,
            double coverage,
            long maxGapMs,
            long headOffsetMs,
            long tailOffsetMs
    ) {
    }
}
