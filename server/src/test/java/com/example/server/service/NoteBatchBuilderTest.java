package com.example.server.service;

import com.example.server.config.NoteBatchProperties;
import com.example.server.dto.NoteInputBatch;
import com.example.server.dto.SegmentPart;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoteBatchBuilderTest {

    @Test
    void noChapterUsesStartOrderAndDynamicPacking() {
        NoteBatchBuilder builder = new NoteBatchBuilder(properties(10, 20, 100));
        VideoContext context = context(
                segment(20, 30, "fifth"),
                segment(0, 10, "first"),
                segment(10, 20, "four"));

        List<NoteInputBatch> batches = builder.build(7L, "VIDEO_NOTE_V1", context);

        assertEquals(2, batches.size());
        assertEquals(List.of("first", "four"), transcripts(batches.get(0)));
        assertEquals(List.of("fifth"), transcripts(batches.get(1)));
        assertEquals(1, batches.get(0).sequenceNo());
        assertEquals(2, batches.get(1).sequenceNo());
        assertEquals(0, batches.get(0).startMs());
        assertEquals(30, batches.get(1).endMs());
    }

    @Test
    void chaptersAreProcessedInPlatformOrderAndNeverCrossBatchBoundary() {
        NoteBatchProperties props = properties(100, 120, 100);
        NoteBatchBuilder builder = new NoteBatchBuilder(props);
        VideoContext context = new VideoContext("source", "goal", List.of(
                new VideoContext.VideoSegment(200, 300, "chapter two", List.of(), List.of(),
                        com.example.server.dto.TranscriptSource.CC, "chapter-2"),
                new VideoContext.VideoSegment(0, 100, "chapter one", List.of(), List.of(),
                        com.example.server.dto.TranscriptSource.CC, "chapter-1")),
                300L, VideoContext.ANALYSIS_VERSION_V2,
                List.of(
                        new VideoChapter("chapter-1", "第一章", 0, 100, 1, "VIEW"),
                        new VideoChapter("empty", "空章节", 100, 200, 1, "VIEW"),
                        new VideoChapter("chapter-2", "第二章", 200, 300, 1, "VIEW")));

        List<NoteInputBatch> batches = builder.build(9L, "VIDEO_NOTE_V1", context);

        assertEquals(2, batches.size(), "empty chapters must not create empty model requests");
        assertEquals(List.of("chapter-1", "chapter-2"),
                batches.stream().map(NoteInputBatch::chapterId).toList());
        assertEquals(List.of("第一章", "第二章"),
                batches.stream().map(NoteInputBatch::chapterTitle).toList());
        assertTrue(batches.get(0).segments().stream().allMatch(part -> "chapter-1".equals(part.chapterId())));
        assertTrue(batches.get(1).segments().stream().allMatch(part -> "chapter-2".equals(part.chapterId())));
    }

    @Test
    void overflowingSegmentIsRetriedInNextBatchWithoutLoss() {
        NoteBatchBuilder builder = new NoteBatchBuilder(properties(10, 20, 100));
        VideoContext context = context(segment(0, 10, "123456"), segment(10, 20, "abcdef"));

        List<NoteInputBatch> batches = builder.build(3L, "VIDEO_NOTE_V1", context);

        assertEquals(2, batches.size());
        assertEquals(List.of("123456"), transcripts(batches.get(0)));
        assertEquals(List.of("abcdef"), transcripts(batches.get(1)));
        assertEquals(12, batches.stream().flatMap(batch -> batch.segments().stream())
                .mapToInt(SegmentPart::inputChars).sum());
        assertFalse(batches.stream().anyMatch(NoteInputBatch::truncated));
    }

    @Test
    void oversizedSegmentIsSplitIntoContinuousPartsWithStableOriginalId() {
        NoteBatchBuilder builder = new NoteBatchBuilder(properties(10, 10, 6));
        VideoContext context = context(new VideoContext.VideoSegment(
                100, 400, "abcdefghijkl", List.of("XYZ"), List.of("frame"),
                com.example.server.dto.TranscriptSource.CC, null));

        List<NoteInputBatch> batches = builder.build(11L, "VIDEO_NOTE_V1", context);
        List<SegmentPart> parts = batches.stream().flatMap(batch -> batch.segments().stream()).toList();

        assertEquals(3, parts.size());
        assertEquals(List.of(1, 2, 3), parts.stream().map(SegmentPart::partNo).toList());
        assertEquals(1, parts.stream().map(SegmentPart::segmentId).distinct().count());
        assertEquals(100, parts.get(0).startMs());
        assertEquals(400, parts.get(2).endMs());
        assertEquals(parts.get(0).endMs(), parts.get(1).startMs());
        assertEquals(parts.get(1).endMs(), parts.get(2).startMs());
        assertEquals("abcdefghijkl", parts.stream().map(SegmentPart::transcript).reduce("", String::concat));
        assertEquals("XYZ", String.join("", parts.stream()
                .flatMap(part -> part.ocrTexts().stream()).toList()));
        assertTrue(parts.stream().allMatch(part -> part.evidenceFrames().equals(List.of("frame"))));
    }

    @Test
    void batchIdIsStableForSameTupleAndChangesWhenProfileChanges() {
        NoteBatchBuilder builder = new NoteBatchBuilder(properties(100, 120, 100));
        VideoContext context = context(segment(0, 10, "text"));

        String first = builder.build(5L, "VIDEO_NOTE_V1", context).get(0).batchId();
        String retry = builder.build(5L, "VIDEO_NOTE_V1", context).get(0).batchId();
        String changed = builder.build(5L, "VIDEO_NOTE_V2", context).get(0).batchId();

        assertEquals(first, retry);
        assertNotEquals(first, changed);
        assertTrue(first.contains("chapter".replace("chapter", "no-chapter")));
    }

    private static NoteBatchProperties properties(long soft, long hard, int partChars) {
        NoteBatchProperties properties = new NoteBatchProperties();
        properties.setModelContextTokens(Math.max(256, hard));
        properties.setBatchSoftInputTokens(soft);
        properties.setBatchHardInputTokens(hard);
        properties.setOutputReserveTokens(0);
        properties.setSafetyReserveTokens(0);
        properties.setSegmentPartMaxChars(partChars);
        properties.setMaxBatches(128);
        properties.setCharsPerToken(1.0);
        return properties;
    }

    private static VideoContext context(VideoContext.VideoSegment... segments) {
        return new VideoContext("source", "goal", List.of(segments));
    }

    private static VideoContext.VideoSegment segment(long start, long end, String text) {
        return new VideoContext.VideoSegment(start, end, text, List.of(), List.of());
    }

    private static List<String> transcripts(NoteInputBatch batch) {
        return batch.segments().stream().map(SegmentPart::transcript).toList();
    }
}
