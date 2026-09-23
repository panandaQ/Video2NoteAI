package com.example.server.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ASR 章节边界切片契约（计划 §5.1 / §7.1）：60 秒边界 ∪ 章节边界、边界归一化与范围映射。
 */
class SegmentedTranscriptionSliceTest {

    @Test
    void boundariesUnionGridAndChapterBounds() {
        List<Long> boundaries = SegmentedTranscriptionService.audioSliceBoundaries(
                List.of(new com.example.server.dto.VideoChapter(
                        "vp-0-0", "章", 90_000, 150_000, 1, "BILIBILI_VIEW_POINT")),
                300_000L);
        assertTrue(boundaries.contains(60_000L), "60 秒网格");
        assertTrue(boundaries.contains(120_000L));
        assertTrue(boundaries.contains(90_000L), "章节起点并入");
        assertTrue(boundaries.contains(150_000L), "章节终点并入");
        assertTrue(boundaries.stream().allMatch(b -> b > 0 && b < 300_000L));
        assertEquals(boundaries, boundaries.stream().sorted().distinct().toList(), "升序去重");
    }

    @Test
    void sliceRangesAlignWithBoundaries() {
        List<Long> boundaries = List.of(90_000L, 150_000L);
        List<long[]> ranges = SegmentedTranscriptionService.sliceRanges(boundaries, 300_000L, 3);
        assertEquals(3, ranges.size());
        assertEquals(0L, ranges.get(0)[0]);
        assertEquals(90_000L, ranges.get(0)[1]);
        assertEquals(90_000L, ranges.get(1)[0]);
        assertEquals(150_000L, ranges.get(1)[1]);
        assertEquals(150_000L, ranges.get(2)[0]);
        assertEquals(300_000L, ranges.get(2)[1]);
    }

    @Test
    void legacyFixedSixtySecondBehaviorWhenNoBoundaries() {
        List<long[]> ranges = SegmentedTranscriptionService.sliceRanges(List.of(), null, 2);
        assertEquals(0L, ranges.get(0)[0]);
        assertEquals(60_000L, ranges.get(0)[1]);
        assertEquals(60_000L, ranges.get(1)[0]);
        assertEquals(120_000L, ranges.get(1)[1]);
    }
}
