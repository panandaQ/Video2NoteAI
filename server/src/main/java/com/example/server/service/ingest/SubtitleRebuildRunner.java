package com.example.server.service.ingest;

import com.example.server.dto.AnalysisInputManifest;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性字幕重建工具：检测 READY 但 Context 仍非 CC 的 B 站媒体，拉字幕并重新提交默认笔记
 * （字幕优先重建 Context 与索引）。
 *
 * <p>判定以「Context 的转录来源」为准，而不是 manifest 的 subtitleStatus：manifest 已 AVAILABLE
 * 但 Context 仍是 ASR（例如上次提交被 {@code DUPLICATE} 挡掉）也必须重建。只由
 * {@code video.import.subtitle-rebuild.enabled=true} 触发，默认关闭；重建是异步的，本 Runner
 * 只负责清理与提交，不等待分析完成。
 */
@Component
@ConditionalOnProperty(name = "video.import.subtitle-rebuild.enabled", havingValue = "true")
public class SubtitleRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SubtitleRebuildRunner.class);

    private final MediaFileMapper mediaFileMapper;
    private final ContentArtifactEnrichmentService enrichmentService;
    private final MediaService mediaService;
    private final VideoNoteTaskPort noteTaskPort;
    private final MinioUtils minioUtils;
    private final AgentCheckpointService checkpointService;

    public SubtitleRebuildRunner(MediaFileMapper mediaFileMapper,
                                 ContentArtifactEnrichmentService enrichmentService,
                                 MediaService mediaService,
                                 VideoNoteTaskPort noteTaskPort,
                                 MinioUtils minioUtils,
                                 AgentCheckpointService checkpointService) {
        this.mediaFileMapper = mediaFileMapper;
        this.enrichmentService = enrichmentService;
        this.mediaService = mediaService;
        this.noteTaskPort = noteTaskPort;
        this.minioUtils = minioUtils;
        this.checkpointService = checkpointService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<MediaFile> ready = mediaFileMapper.findReadyBilibili();
        int rebuilt = 0;
        int skipped = 0;
        for (MediaFile media : ready) {
            if (media.getContentHash() == null || media.getContentHash().isBlank()) {
                skipped++;
                continue;
            }
            try {
                if (hasCcContext(media.getId())) {
                    skipped++;
                    continue;
                }
                rebuild(media);
                rebuilt++;
            } catch (RuntimeException e) {
                log.error("subtitle_rebuild_failed mediaId={}", media.getId(), e);
            }
        }
        log.info("subtitle_rebuild_done ready={} rebuilt={} skipped={}", ready.size(), rebuilt, skipped);
    }

    /** Context 全 CC 视为已就绪；缺失、空或仍含 ASR 都视为需要重建。 */
    private boolean hasCcContext(Long mediaId) {
        try {
            VideoContext context = checkpointService.loadContext(mediaId);
            if (context == null || context.segments().isEmpty()) {
                return false;
            }
            return context.segments().stream().allMatch(s -> s.source() == TranscriptSource.CC);
        } catch (RuntimeException e) {
            log.warn("subtitle_rebuild_context_read_failed mediaId={}", mediaId, e);
            return false;
        }
    }

    private void rebuild(MediaFile media) {
        String hash = media.getContentHash();
        AnalysisInputManifest manifest = enrichmentService.loadManifest(hash);
        boolean subtitleReady = manifest != null
                && AnalysisInputManifest.SUBTITLE_AVAILABLE.equals(manifest.subtitleStatus());
        if (!subtitleReady) {
            // 字幕未就绪：删旧 manifest + subtitle-zh.json，重新拉字幕（首写者胜出会跳过已有对象）。
            minioUtils.removeObject(ContentArtifactEnrichmentService.manifestObject(hash));
            minioUtils.removeObject(ContentArtifactEnrichmentService.subtitleObject(hash));
            ContentArtifactEnrichmentService.EnrichmentResult result = enrichmentService.ensureArtifacts(media);
            if (result != ContentArtifactEnrichmentService.EnrichmentResult.READY) {
                log.warn("subtitle_rebuild_enrich_in_progress mediaId={}", media.getId());
                return;
            }
        }
        // 清理运行时产物（Context/Chunk checkpoint、Qdrant、关键帧）：否则分块因版本匹配复用旧 ASR 快照。
        mediaService.purgeRuntimeArtifacts(media.getId());
        // 重新提交默认笔记：buildContext 读到 AVAILABLE manifest，字幕优先重建 Context 与索引。
        noteTaskPort.submitDefaultNote(media.getId());
        log.info("subtitle_rebuild_submitted mediaId={} contentHash={}", media.getId(), hash);
    }
}
