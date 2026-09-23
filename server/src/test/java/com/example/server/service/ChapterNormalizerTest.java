package com.example.server.service;

import com.example.server.dto.PlayerViewPoint;
import com.example.server.dto.VideoChapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * View 章节规范化契约（计划 §5.1 / §7.1，D-074）。
 */
class ChapterNormalizerTest {

    @Test
    void emptyViewPointsProduceNoChapters() {
        assertTrue(ChapterNormalizer.normalize(List.of(), 60_000L).isEmpty());
    }

    @Test
    void preservesOrderAndGeneratesStableIds() {
        List<VideoChapter> chapters = ChapterNormalizer.normalize(List.of(
                new PlayerViewPoint(" 开场 ", 0.0, 10.0, 1),
                new PlayerViewPoint("正片", 10.0, 50.0, 1)), 60_000L);
        assertEquals(2, chapters.size());
        assertEquals("vp-0-0", chapters.get(0).id());
        assertEquals("vp-1-10000", chapters.get(1).id());
        assertEquals("开场", chapters.get(0).title(), "标题去空白，只用于展示");
        assertEquals(0L, chapters.get(0).startMs());
        assertEquals(10_000L, chapters.get(0).endMs());
        assertEquals("BILIBILI_VIEW_POINT", chapters.get(0).source());
        assertEquals(1, chapters.get(0).platformType());
    }

    @Test
    void smallOverrunIsClampedToDuration() {
        List<VideoChapter> chapters = ChapterNormalizer.normalize(List.of(
                new PlayerViewPoint("结尾", 50.0, 61.5, 1)), 60_000L);
        assertEquals(60_000L, chapters.get(0).endMs(), "61.5s 相对 60s 越界 1.5s ≤ 2s，clamp");
    }

    @Test
    void largeOverrunIsRejected() {
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("越界", 50.0, 70.0, 1)), 60_000L));
    }

    @Test
    void overlapOutOfOrderAndNaNRejectedAsWhole() {
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("a", 0, 20, 1),
                        new PlayerViewPoint("b", 10, 30, 1)), 60_000L));
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("a", 30, 40, 1),
                        new PlayerViewPoint("b", 10, 20, 1)), 60_000L));
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("a", Double.NaN, 20, 1)), 60_000L));
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("a", 0, -5, 1)), 60_000L));
    }

    @Test
    void blankTitleRejected() {
        assertThrows(ChapterNormalizer.InvalidChapterDataException.class, () ->
                ChapterNormalizer.normalize(List.of(
                        new PlayerViewPoint("  ", 0, 10, 1)), 60_000L));
    }

    @Test
    void gapsBetweenChaptersAreAllowedAsUnassignedMaterial() {
        List<VideoChapter> chapters = ChapterNormalizer.normalize(List.of(
                new PlayerViewPoint("a", 0, 10, 1),
                new PlayerViewPoint("b", 15, 20, 1)), 60_000L);
        assertEquals(2, chapters.size(), "片头/间隙/片尾留白合法，作为未分章素材");
    }
}
