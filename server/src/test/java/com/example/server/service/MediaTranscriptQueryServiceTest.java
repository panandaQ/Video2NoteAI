package com.example.server.service;

import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaTranscriptQueryServiceTest {

    @Mock
    private MediaService mediaService;
    @Mock
    private AgentCheckpointService checkpointService;
    @InjectMocks
    private MediaTranscriptQueryService service;

    @Test
    void returnsOnlyTranscriptSegmentsFromOwnedMediaContext() {
        when(mediaService.requireOwnedMedia(27L, 7L)).thenReturn(new MediaFile());
        when(checkpointService.loadContext(27L)).thenReturn(new VideoContext(
                "source", "", List.of(
                new VideoContext.VideoSegment(0, 5000, "第一句", List.of("画面文字"),
                        List.of(), TranscriptSource.CC, "chapter-1"),
                new VideoContext.VideoSegment(5000, 10000, "", List.of("只有 OCR"),
                        List.of(), TranscriptSource.CC, "chapter-1"),
                new VideoContext.VideoSegment(10000, 15000, "第二句", List.of(),
                        List.of(), TranscriptSource.ASR, "chapter-2"))));

        var response = service.transcript(7L, 27L);

        assertTrue(response.available());
        assertEquals(2, response.segments().size());
        assertEquals("第一句", response.segments().get(0).text());
        assertEquals("CC", response.segments().get(0).source());
        assertEquals("第二句", response.segments().get(1).text());
    }

    @Test
    void returnsUnavailableWhenContextIsNotReady() {
        when(mediaService.requireOwnedMedia(27L, 7L)).thenReturn(new MediaFile());

        var response = service.transcript(7L, 27L);

        assertFalse(response.available());
        assertTrue(response.segments().isEmpty());
    }

    @Test
    void hidesForeignMediaAsNotFound() {
        when(mediaService.requireOwnedMedia(27L, 7L)).thenThrow(new SecurityException("forbidden"));

        assertThrows(NoSuchElementException.class, () -> service.transcript(7L, 27L));
    }
}
