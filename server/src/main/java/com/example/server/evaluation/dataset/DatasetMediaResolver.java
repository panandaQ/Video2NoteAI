package com.example.server.evaluation.dataset;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.dto.VideoChunk;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** 把稳定 contentHash mediaRef 解析为当前环境可用的媒体与 V2 Chunk，不向报告暴露 mediaId。 */
@Component
public class DatasetMediaResolver {

    private final MediaFileMapper mediaFileMapper;
    private final AgentCheckpointService checkpointService;

    public DatasetMediaResolver(MediaFileMapper mediaFileMapper, AgentCheckpointService checkpointService) {
        this.mediaFileMapper = mediaFileMapper;
        this.checkpointService = checkpointService;
    }

    public ResolvedMedia resolve(String mediaRef) {
        String contentHash = contentHash(mediaRef);
        List<MediaFile> candidates = mediaFileMapper.selectList(new QueryWrapper<MediaFile>()
                .eq("content_hash", contentHash)
                .orderByAsc("id"));
        for (MediaFile candidate : candidates) {
            List<VideoChunk> chunks = checkpointService.loadChunks(candidate.getId());
            if (chunks != null && !chunks.isEmpty()) {
                return new ResolvedMedia(candidate.getId(), chunks);
            }
        }
        throw new IllegalArgumentException(candidates.isEmpty()
                ? "MEDIA_REF_NOT_FOUND" : "MEDIA_REF_V2_CHUNKS_MISSING");
    }

    static String contentHash(String mediaRef) {
        if (mediaRef == null || mediaRef.isBlank()) throw new IllegalArgumentException("MEDIA_REF_REQUIRED");
        String normalized = mediaRef.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sha256:")) return requiredSuffix(normalized, "sha256:".length());
        if (lower.startsWith("contenthash:")) return requiredSuffix(normalized, "contentHash:".length());
        throw new IllegalArgumentException("MEDIA_REF_FORMAT_UNSUPPORTED");
    }

    private static String requiredSuffix(String value, int offset) {
        String suffix = value.substring(offset).trim().toLowerCase(Locale.ROOT);
        if (suffix.isEmpty()) throw new IllegalArgumentException("MEDIA_REF_HASH_REQUIRED");
        return suffix;
    }

    public record ResolvedMedia(Long mediaId, List<VideoChunk> chunks) {
        public ResolvedMedia {
            if (mediaId == null || mediaId <= 0) throw new IllegalArgumentException("RESOLVED_MEDIA_INVALID");
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }
}
