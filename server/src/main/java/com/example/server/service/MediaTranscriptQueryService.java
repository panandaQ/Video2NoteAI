package com.example.server.service;

import com.example.server.dto.MediaTranscriptResponse;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;

/** 从内容级 V2 检查点读取媒体字幕；不重新转录，也不把 OCR 冒充字幕。 */
@Service
public class MediaTranscriptQueryService {

    private final MediaService mediaService;
    private final AgentCheckpointService checkpointService;

    public MediaTranscriptQueryService(MediaService mediaService,
                                       AgentCheckpointService checkpointService) {
        this.mediaService = mediaService;
        this.checkpointService = checkpointService;
    }

    public MediaTranscriptResponse transcript(Long userId, Long mediaId) {
        requireOwned(userId, mediaId);
        VideoContext context = checkpointService.loadContext(mediaId);
        if (context == null) {
            return new MediaTranscriptResponse(mediaId, false, List.of());
        }
        List<MediaTranscriptResponse.Segment> segments = context.segments().stream()
                .filter(segment -> !segment.transcript().isBlank())
                .map(this::toSegment)
                .toList();
        return new MediaTranscriptResponse(mediaId, !segments.isEmpty(), segments);
    }

    private MediaTranscriptResponse.Segment toSegment(VideoContext.VideoSegment segment) {
        return new MediaTranscriptResponse.Segment(
                segment.startMs(),
                segment.endMs(),
                segment.transcript(),
                segment.source().name(),
                segment.chapterId());
    }

    private void requireOwned(Long userId, Long mediaId) {
        try {
            mediaService.requireOwnedMedia(mediaId, userId);
        } catch (SecurityException exception) {
            throw new NoSuchElementException("媒体不存在");
        }
    }
}
