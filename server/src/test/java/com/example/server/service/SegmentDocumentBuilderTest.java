package com.example.server.service;

import com.example.server.config.MinuteRagProperties;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import com.example.server.dto.TranscriptSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentDocumentBuilderTest {
    @Test
    void buildsStableMinuteDocumentsFromContextWithoutChunkBoundaries() {
        MinuteRagProperties properties = new MinuteRagProperties();
        SegmentDocumentBuilder builder = new SegmentDocumentBuilder(properties);
        VideoContext context = new VideoContext("source", "", List.of(
                new VideoContext.VideoSegment(0, 60_000, "第一段", List.of("标题"), List.of("frame"), TranscriptSource.CC, "c1"),
                new VideoContext.VideoSegment(60_000, 120_000, "第二段", List.of(), List.of(), TranscriptSource.CC, "c1")),
                120_000L, VideoContext.ANALYSIS_VERSION_V2,
                List.of(new VideoChapter("c1", "章节一", 0, 120_000, 1, "TEST")));

        var documents = builder.build(42L, context);

        assertEquals(List.of("42:0:60000", "42:60000:120000"),
                documents.stream().map(d -> d.segmentRef()).toList());
        assertEquals("章节一", documents.get(0).chapterTitle());
        assertEquals("CC", documents.get(0).transcriptSource());
        assertEquals("第一段 标题 章节一", documents.get(0).lexicalText());
    }
}
