package com.example.server.service.ingest;

import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.MediaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性笔记重建工具：清理所有 READY 媒体的运行时产物（Context/Chunk checkpoint、Qdrant、关键帧）
 * 并重新提交默认笔记，使分块与检索索引按最新分块算法（批量语义清洗）重建。
 *
 * <p>与 {@link SubtitleRebuildRunner} 的区别：后者只为「Context 仍非 CC」的媒体重建字幕，会跳过
 * 全 CC 媒体；本 Runner 面向分块算法升级，不区分转录来源，对所有 READY 媒体都清理并重建——因为
 * 分块摘要由 200 字升级为批量 500 字 + 纠错别名后，即使转录来源不变也必须重建，否则
 * {@code ensureChunks} 因 {@code analysisVersion} 匹配而复用旧分块。只由
 * {@code video.import.note-rebuild.enabled=true} 触发，默认关闭；重建是异步的，本 Runner 只负责
 * 清理与提交，不等待分析完成。
 */
@Component
@ConditionalOnProperty(name = "video.import.note-rebuild.enabled", havingValue = "true")
public class NoteRebuildRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NoteRebuildRunner.class);

    private final MediaFileMapper mediaFileMapper;
    private final MediaService mediaService;
    private final VideoNoteTaskPort noteTaskPort;

    public NoteRebuildRunner(MediaFileMapper mediaFileMapper,
                             MediaService mediaService,
                             VideoNoteTaskPort noteTaskPort) {
        this.mediaFileMapper = mediaFileMapper;
        this.mediaService = mediaService;
        this.noteTaskPort = noteTaskPort;
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
                // 清理运行时产物：否则 ensureChunks 因版本匹配复用旧分块，不会应用新分块算法。
                mediaService.purgeRuntimeArtifacts(media.getId());
                noteTaskPort.submitDefaultNote(media.getId());
                rebuilt++;
                log.info("note_rebuild_submitted mediaId={}", media.getId());
            } catch (RuntimeException e) {
                log.error("note_rebuild_failed mediaId={}", media.getId(), e);
            }
        }
        log.info("note_rebuild_done ready={} rebuilt={} skipped={}", ready.size(), rebuilt, skipped);
    }
}
