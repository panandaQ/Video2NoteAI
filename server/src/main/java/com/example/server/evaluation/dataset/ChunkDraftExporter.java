package com.example.server.evaluation.dataset;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** 从正式 V2 Checkpoint 导出不含环境 mediaId、向量或对象地址的出题底稿。 */
@Service
public class ChunkDraftExporter {

    private final AgentCheckpointService checkpointService;
    private final MediaFileMapper mediaFileMapper;

    public ChunkDraftExporter(AgentCheckpointService checkpointService, MediaFileMapper mediaFileMapper) {
        this.checkpointService = checkpointService;
        this.mediaFileMapper = mediaFileMapper;
    }

    public ExportBundle export(ExportRequest request) {
        if (request == null || request.media() == null || request.media().isEmpty()) {
            throw new IllegalArgumentException("EXPORT_MEDIA_REQUIRED");
        }
        return new ExportBundle("chunk-draft-v1", request.media().stream().map(this::exportOne).toList());
    }

    private ExportedVideo exportOne(MediaSelection selection) {
        if (selection == null || selection.mediaId() == null || selection.mediaId() <= 0) {
            throw new IllegalArgumentException("EXPORT_MEDIA_ID_INVALID");
        }
        MediaFile media = mediaFileMapper.selectById(selection.mediaId());
        if (media == null) throw new IllegalArgumentException("EXPORT_MEDIA_NOT_FOUND");
        String contentHash = normalizedHash(media.getContentHash());
        if (contentHash == null) throw new IllegalArgumentException("MEDIA_CONTENT_HASH_MISSING");

        List<VideoChunk> chunks = checkpointService.loadChunks(selection.mediaId());
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalArgumentException("MEDIA_V2_CHUNKS_MISSING");
        }
        VideoContext context = checkpointService.loadContext(selection.mediaId());
        Long durationMs = context != null && context.durationMs() != null
                ? context.durationMs() : media.getSourceDurationMs();
        List<ExportedChunk> exportedChunks = java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> exportedChunk(index + 1, chunks.get(index)))
                .toList();
        int segmentCount = exportedChunks.stream().mapToInt(chunk -> chunk.segments().size()).sum();
        return new ExportedVideo(
                "sha256:" + contentHash,
                requiredText(selection.sourceVideoTag(), "SOURCE_VIDEO_TAG_REQUIRED"),
                title(media),
                text(selection.sourceDistributionNote()),
                durationMs,
                exportedChunks.size(),
                segmentCount,
                exportedChunks);
    }

    private ExportedChunk exportedChunk(int index, VideoChunk chunk) {
        List<ExportedSegment> segments = chunk.rawSegments().stream()
                .map(segment -> new ExportedSegment(
                        segment.startMs(), segment.endMs(), segment.source().name(),
                        segment.transcript(), segment.ocrTexts()))
                .toList();
        return new ExportedChunk(index, chunk.startMs(), chunk.endMs(), sourceType(chunk),
                text(chunk.chapterTitle()), chunk.segmentSummary(), chunk.keywords(), segments);
    }

    private String sourceType(VideoChunk chunk) {
        List<String> transcriptSources = chunk.rawSegments().stream()
                .filter(segment -> segment.transcript() != null && !segment.transcript().isBlank())
                .map(segment -> segment.source().name())
                .distinct()
                .toList();
        if (transcriptSources.size() == 1) return transcriptSources.getFirst();
        if (transcriptSources.size() > 1) return "MIXED";
        boolean hasOcr = chunk.rawSegments().stream()
                .flatMap(segment -> segment.ocrTexts().stream())
                .anyMatch(value -> value != null && !value.isBlank());
        return hasOcr ? "OCR" : "UNKNOWN";
    }

    private String title(MediaFile media) {
        if (media.getSourceTitle() != null && !media.getSourceTitle().isBlank()) {
            return media.getSourceTitle().trim();
        }
        return text(media.getFilename());
    }

    private String normalizedHash(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String requiredText(String value, String code) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
        return value.trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    public record ExportRequest(List<MediaSelection> media) {
        public ExportRequest {
            media = media == null ? List.of() : List.copyOf(media);
        }
    }

    public record MediaSelection(Long mediaId, String sourceVideoTag, String sourceDistributionNote) {
    }

    public record ExportBundle(String schemaVersion, List<ExportedVideo> videos) {
        public ExportBundle {
            videos = videos == null ? List.of() : List.copyOf(videos);
        }
    }

    public record ExportedVideo(String mediaRef,
                                String sourceVideoTag,
                                String title,
                                String sourceDistributionNote,
                                Long durationMs,
                                int chunkCount,
                                int segmentCount,
                                List<ExportedChunk> chunks) {
        public ExportedVideo {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    public record ExportedChunk(int chunkIndex,
                                long startMs,
                                long endMs,
                                String sourceType,
                                String chapterTitle,
                                String summary,
                                List<String> keywords,
                                List<ExportedSegment> segments) {
        public ExportedChunk {
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    public record ExportedSegment(long startMs,
                                  long endMs,
                                  String source,
                                  String transcript,
                                  List<String> ocrTexts) {
        public ExportedSegment {
            transcript = transcript == null ? "" : transcript;
            ocrTexts = ocrTexts == null ? List.of() : List.copyOf(ocrTexts);
        }
    }
}
