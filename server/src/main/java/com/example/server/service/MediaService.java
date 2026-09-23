package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.dto.MediaImportStatus;
import com.example.server.dto.VideoContext;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.utils.MinioUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    private final MediaFileMapper mediaFileMapper;
    private final StringRedisTemplate redisTemplate;
    private final MinioUtils minioUtils;
    private final ObjectMapper objectMapper;
    private final AgentCheckpointService checkpointService;
    private final AgentTelemetry telemetry;
    private final QdrantVectorStore vectorStore;
    private final VideoContextService videoContextService;
    private final List<MediaDeletionListener> deletionListeners;

    private static final String MEDIA_MD5_KEY_PREFIX = "media:md5:";
    private static final Set<String> VIDEO_SUFFIXES = Set.of(
            ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v");

    public MediaService(MediaFileMapper mediaFileMapper,
                        StringRedisTemplate redisTemplate,
                        MinioUtils minioUtils,
                        ObjectMapper objectMapper,
                        AgentCheckpointService checkpointService,
                        AgentTelemetry telemetry,
                        QdrantVectorStore vectorStore,
                        VideoContextService videoContextService,
                        List<MediaDeletionListener> deletionListeners) {
        this.mediaFileMapper = mediaFileMapper;
        this.redisTemplate = redisTemplate;
        this.minioUtils = minioUtils;
        this.objectMapper = objectMapper;
        this.checkpointService = checkpointService;
        this.telemetry = telemetry;
        this.vectorStore = vectorStore;
        this.videoContextService = videoContextService;
        this.deletionListeners = deletionListeners == null ? List.of() : List.copyOf(deletionListeners);
    }

    public String calculateMd5(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            return calculateMd5(inputStream);
        }
    }

    public String calculateMd5(File file) throws IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return calculateMd5(inputStream);
        }
    }

    public void rememberContentHash(Long mediaId, String md5) {
        if (mediaId == null || md5 == null || md5.isBlank()) return;
        try {
            redisTemplate.opsForValue().set(MEDIA_MD5_KEY_PREFIX + mediaId, md5);
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_write_failed mediaId={}", mediaId, e);
        }
    }

    public MediaFile saveUploadedMedia(String filename, String fileUrl, Long userId, String md5) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(normalizeVideoFilename(filename));
        mediaFile.setFilePath(fileUrl);
        mediaFile.setStatus(MediaImportStatus.COMPLETED);
        mediaFile.setUploadTime(LocalDateTime.now());
        mediaFile.setUserId(userId);
        mediaFile.setContentHash(md5);
        try {
            mediaFileMapper.insert(mediaFile);
            rememberContentHash(mediaFile.getId(), md5);
            invalidateUserList(userId);
            return mediaFile;
        } catch (RuntimeException e) {
            removeUploadedObject(fileUrl, e);
            throw e;
        }
    }

    /**
     * 用户媒体列表。
     *
     * <p>只展示可用媒体：旧本地上传的 {@code COMPLETED} 与 URL 导入默认笔记完成的 {@code READY}；
     * 所有中间态（排队、获取中、入库完成但笔记未完成）与失败态都不出现，避免用户看到无法分析的条目。
     * 媒体进入可见集合或被删除时必须失效本缓存。
     *
     * <p>投影包含导入条目的展示字段（封面、时长、UP 主、来源平台与原链接、文件大小）：URL 导入的视频
     * 只有在这些字段齐全时才是一个"本地库条目"，否则用户看到的是一个没有缩略图、没有时长的空壳。
     * 旧上传记录没有来源字段，对应值为 {@code null}。
     */
    public List<MediaFile> listByUser(Long userId) {
        String cacheKey = userListKey(userId);
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, new TypeReference<List<MediaFile>>() { });
            }
        } catch (Exception e) {
            log.warn("media_list_cache_read_failed userId={}", userId, e);
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(
                query.select("id", "filename", "status", "cover_url", "upload_time",
                                "source_title", "source_author", "source_duration_ms",
                                "platform", "canonical_url", "file_size")
                        .eq("user_id", userId)
                        .in("status", MediaImportStatus.COMPLETED.name(), MediaImportStatus.READY.name())
                        .orderByDesc("id"));
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, objectMapper.writeValueAsString(mediaFiles), 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("media_list_cache_write_failed userId={}", userId, e);
        }
        return mediaFiles;
    }

    public String contentHash(Long mediaId) {
        try {
            String cached = redisTemplate.opsForValue().get(MEDIA_MD5_KEY_PREFIX + mediaId);
            if (cached != null && !cached.isBlank()) return cached;
        } catch (RuntimeException e) {
            log.warn("media_hash_cache_read_failed mediaId={}", mediaId, e);
        }

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        String persisted = mediaFile == null ? null : mediaFile.getContentHash();
        rememberContentHash(mediaId, persisted);
        return persisted;
    }

    public void deleteOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = requireOwnedMedia(mediaId, userId);
        // 删除前先让引用该媒体的业务记录收敛（契约 §9.4）：媒体行一旦消失，
        // 仍在等待的导入子项就再也等不到状态推进，父任务会永久停在处理中。
        notifyBeforeMediaDeleted(mediaId, userId);
        mediaFileMapper.deleteById(mediaId);
        // D-070：用户删除只移除**自己的内容库条目与私有数据**，不删除共享内容资产。
        // 视频字节与封面现在是内容寻址的共享对象，可能正被其他用户引用；即使零引用也不自动清理
        // （规格 §3.2 / AC-16），删了会让"删自己那条"变成"删别人的视频"。对象回收只保留管理员人工路径。
        purgeRuntimeArtifacts(mediaId);
        invalidateUserList(userId);
    }

    /**
     * 删除联动回调必须隔离异常：删除媒体是用户意图，不能因为下游业务记账失败而失败，
     * 遗留的悬挂记录由恢复扫描兜底。
     */
    private void notifyBeforeMediaDeleted(Long mediaId, Long userId) {
        for (MediaDeletionListener listener : deletionListeners) {
            try {
                listener.beforeMediaDeleted(mediaId, userId);
            } catch (RuntimeException e) {
                log.warn("media_deletion_listener_failed mediaId={} listener={}",
                        mediaId, listener.getClass().getSimpleName(), e);
            }
        }
    }

    public boolean exists(Long mediaId) {
        return mediaId != null && mediaFileMapper.selectById(mediaId) != null;
    }

    public void purgeRuntimeArtifacts(Long mediaId) {
        VideoContext context = null;
        try {
            context = checkpointService.loadContext(mediaId);
        } catch (RuntimeException e) {
            log.warn("media_evidence_manifest_read_failed mediaId={}", mediaId, e);
        }
        videoContextService.deleteEvidenceFrames(context);
        try {
            redisTemplate.delete(List.of(
                    MEDIA_MD5_KEY_PREFIX + mediaId,
                    "transcription:active:" + mediaId,
                    "transcription:state:" + mediaId));
            checkpointService.deleteMedia(mediaId);
            telemetry.deleteTask(mediaId);
            vectorStore.deleteMedia(mediaId);
        } catch (RuntimeException e) {
            log.warn("media_runtime_cleanup_failed mediaId={}", mediaId, e);
        }
    }

    public void invalidateUserList(Long userId) {
        if (userId == null) return;
        try {
            redisTemplate.delete(userListKey(userId));
        } catch (RuntimeException e) {
            log.warn("media_list_cache_invalidation_failed userId={}", userId, e);
        }
    }

    public String readableSource(String source) {
        return minioUtils.readableSource(source);
    }

    public String normalizeVideoFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("视频文件名不能为空");
        }
        String normalized = filename.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new IllegalArgumentException("视频文件名无效或过长");
        }
        String suffix = fileSuffix(normalized).toLowerCase(java.util.Locale.ROOT);
        if (!VIDEO_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅支持 MP4、MOV、MKV、AVI、WEBM 和 M4V 视频");
        }
        return normalized;
    }

    public MediaFile requireOwnedMedia(Long mediaId, Long userId) {
        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) throw new NoSuchElementException("文件不存在");
        if (!Objects.equals(mediaFile.getUserId(), userId)) {
            throw new SecurityException("无权访问该文件");
        }
        return mediaFile;
    }

    private String calculateMd5(InputStream inputStream) throws IOException {
        MessageDigest digest = md5Digest();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is not available", e);
        }
    }

    private String userListKey(Long userId) {
        return "media:list:v2:user:" + userId;
    }

    private String fileSuffix(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    private void removeUploadedObject(String fileUrl, RuntimeException originalError) {
        try {
            minioUtils.removeFile(fileUrl);
        } catch (RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
            log.warn("uploaded_object_rollback_failed path={}", fileUrl, cleanupError);
        }
    }
}
