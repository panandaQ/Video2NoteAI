package com.example.server.evaluation.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.server.dto.MediaImportStatus;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 把环境无关的 contentHash mediaRef 解析为本次环境中的 READY 用户媒体。 */
@Component
public class MediaReferenceResolver {

    private static final String SHA256_PREFIX = "sha256:";

    private final MediaFileMapper mediaFileMapper;

    public MediaReferenceResolver(MediaFileMapper mediaFileMapper) {
        this.mediaFileMapper = mediaFileMapper;
    }

    public ResolvedMedia resolve(String mediaRef, Long userId) {
        String contentHash = normalize(mediaRef);
        LambdaQueryWrapper<MediaFile> query = new LambdaQueryWrapper<MediaFile>()
                .eq(MediaFile::getContentHash, contentHash)
                .eq(MediaFile::getStatus, MediaImportStatus.READY)
                .orderByAsc(MediaFile::getId);
        if (userId != null) query.eq(MediaFile::getUserId, userId);
        List<MediaFile> matches = mediaFileMapper.selectList(query);
        if (matches == null || matches.isEmpty()) {
            throw new IllegalArgumentException("no READY media found for mediaRef=" + mediaRef
                    + (userId == null ? "" : ", userId=" + userId));
        }
        MediaFile media = matches.getFirst();
        return new ResolvedMedia(media.getUserId(), media.getId(), contentHash);
    }

    static String normalize(String mediaRef) {
        if (mediaRef == null || mediaRef.isBlank()) {
            throw new IllegalArgumentException("mediaRef is required");
        }
        String normalized = mediaRef.trim();
        if (normalized.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())) {
            normalized = normalized.substring(SHA256_PREFIX.length());
        }
        if (normalized.isBlank() || normalized.contains("<") || normalized.contains(">")) {
            throw new IllegalArgumentException("mediaRef contains an unresolved placeholder");
        }
        return normalized;
    }

    public record ResolvedMedia(Long userId, Long mediaId, String contentHash) { }
}
