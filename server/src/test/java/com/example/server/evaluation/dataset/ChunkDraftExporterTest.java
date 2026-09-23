package com.example.server.evaluation.dataset;

import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChunkDraftExporterTest {

    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final ChunkDraftExporter exporter = new ChunkDraftExporter(checkpointService, mediaFileMapper);

    @Test
    void exportsStableContentHashReferenceWithoutEnvironmentMediaId() throws Exception {
        MediaFile media = media(62L, "7780B0EDCDF062403BC0903A705AB444", "武侠史", 120_000L);
        VideoContext.VideoSegment segment = new VideoContext.VideoSegment(
                10_000, 20_000, "字幕原文", List.of("OCR"), List.of(), TranscriptSource.CC, "chapter-1");
        VideoChunk chunk = new VideoChunk(
                0, 60_000, "摘要", List.of("武侠"), List.of(segment), List.of(0.1, 0.2),
                VideoContext.ANALYSIS_VERSION_V2, "chapter-1", "起源", 0L, 60_000L);
        when(mediaFileMapper.selectById(62L)).thenReturn(media);
        when(checkpointService.loadChunks(62L)).thenReturn(List.of(chunk));
        when(checkpointService.loadContext(62L)).thenReturn(new VideoContext(
                "video.mp4", "", List.of(segment), 120_000L,
                VideoContext.ANALYSIS_VERSION_V2, List.of()));

        ChunkDraftExporter.ExportBundle result = exporter.export(new ChunkDraftExporter.ExportRequest(List.of(
                new ChunkDraftExporter.MediaSelection(62L, "video-A-wuxia", "CC 字幕主导"))));

        assertEquals(1, result.videos().size());
        ChunkDraftExporter.ExportedVideo video = result.videos().getFirst();
        assertEquals("sha256:7780b0edcdf062403bc0903a705ab444", video.mediaRef());
        assertEquals("武侠史", video.title());
        assertEquals(1, video.chunkCount());
        assertEquals(1, video.segmentCount());
        assertEquals("CC", video.chunks().getFirst().sourceType());
        assertEquals("字幕原文", video.chunks().getFirst().segments().getFirst().transcript());
        assertFalse(new ObjectMapper().valueToTree(video).has("mediaId"));
    }

    @Test
    void rejectsMediaWithoutContentHash() {
        when(mediaFileMapper.selectById(7L)).thenReturn(media(7L, " ", "标题", 10L));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> exporter.export(new ChunkDraftExporter.ExportRequest(List.of(
                        new ChunkDraftExporter.MediaSelection(7L, "video-X", "note")))));

        assertEquals("MEDIA_CONTENT_HASH_MISSING", error.getMessage());
    }

    private MediaFile media(Long id, String contentHash, String title, Long durationMs) {
        MediaFile media = new MediaFile();
        media.setId(id);
        media.setContentHash(contentHash);
        media.setSourceTitle(title);
        media.setSourceDurationMs(durationMs);
        return media;
    }
}
