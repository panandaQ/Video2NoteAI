package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索索引的取材与就绪判定（D-069）。
 *
 * <p>RAG 索引的数据源是媒体的分块快照（{@code media:chunks}），它同时携带分块文本与向量，
 * 因此"分块快照存在且非空"就是"可以立刻检索"的充分条件——在线检索由 {@link QdrantVectorStore}
 * 提供向量打分，向量库不可用时同一份分块仍可走内存余弦与关键词打分（GraderDegradation 语义）。
 *
 * <p>为什么要独立成一层：分块的"构建 + 落盘 + 写索引"原先只写在长视频上下文裁剪里，
 * 而导入链路现在需要在两个新位置确认它——跨用户复用结果时（把共享快照挂到当前 mediaId）
 * 和默认笔记完成时（索引没就绪就不许进入 {@code READY}）。三处共用同一段逻辑，
 * 避免"某条路径忘了写索引，任务却显示完成"。
 *
 * <p>优化空间（本期不做）：分块与向量目前按 {@code mediaId} 复制一份，检索因此可以沿用现有的
 * 单视频过滤条件；内容级共享完全落地后可以改成"内容版本级一份 + 检索按用户内容库过滤"，
 * 届时本类只需替换取材来源，调用方不变。
 */
@Service
public class MediaIndexService {

    private static final Logger log = LoggerFactory.getLogger(MediaIndexService.class);

    private final AgentCheckpointService checkpointService;
    private final VideoChunkingService chunkingService;
    private final VideoEvidenceRetrievalService retrievalService;

    public MediaIndexService(AgentCheckpointService checkpointService,
                             VideoChunkingService chunkingService,
                             VideoEvidenceRetrievalService retrievalService) {
        this.checkpointService = checkpointService;
        this.chunkingService = chunkingService;
        this.retrievalService = retrievalService;
    }

    /** 该媒体是否已有可检索的 V2 分块快照（D-079：版本缺省的旧快照不算"就绪"）。 */
    public boolean isIndexed(Long mediaId) {
        if (mediaId == null) {
            return false;
        }
        try {
            List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
            return chunks != null && !chunks.isEmpty()
                    && chunks.get(0).analysisVersion() != null;
        } catch (RuntimeException e) {
            log.warn("media_index_read_failed mediaId={}", mediaId, e);
            return false;
        }
    }

    /**
     * 确保该媒体可以检索：版本匹配的已有分块直接用；否则用给定上下文构建并写索引。
     *
     * @param context 构建分块的素材（含片段与章节）；为 {@code null} 时只做检查
     * @return 构建或复用到的分块；空列表表示当前还构建不出来（调用方应保持处理中并等待重试）
     */
    public ChunkSnapshot ensureChunks(Long mediaId, VideoContext context) {
        List<VideoChunk> existing = mediaId == null ? null : loadChunksQuietly(mediaId);
        boolean versionMatches = existing != null && !existing.isEmpty()
                && java.util.Objects.equals(
                        context == null ? null : context.analysisVersion(),
                        existing.get(0).analysisVersion());
        if (versionMatches) {
            return new ChunkSnapshot(existing, true);
        }
        if (context == null || context.segments().isEmpty()) {
            return new ChunkSnapshot(List.of(), false);
        }
        List<VideoChunk> built = chunkingService.build(
                context.segments(), context.chapters(), context.analysisVersion());
        if (built.isEmpty()) {
            return new ChunkSnapshot(List.of(), false);
        }
        if (mediaId != null) {
            saveAndIndex(mediaId, built);
        }
        return new ChunkSnapshot(built, false);
    }

    /** 兼容旧调用方：只传片段、无章节。 */
    public ChunkSnapshot ensureChunks(Long mediaId, List<VideoContext.VideoSegment> segments) {
        return ensureChunks(mediaId, segments == null ? null
                : new VideoContext("memory://segments", "", segments));
    }

    /**
     * 把一批分块挂到该媒体并写入检索索引。
     *
     * <p>先落盘再写索引：向量库是加速器，检查点才是可重建的真源。反过来会在向量写入成功、
     * 检查点失败时留下"检索能命中但证据装载不回来"的不一致状态。
     */
    public void saveAndIndex(Long mediaId, List<VideoChunk> chunks) {
        checkpointService.saveChunks(mediaId, chunks);
        retrievalService.index(mediaId, chunks);
    }

    /**
     * 默认笔记已经完成时用：确认该媒体可以立刻被问答检索。
     *
     * <p>分块与上下文都还没有时返回 {@code false}——调用方必须保持处理中，由恢复扫描重投，
     * 而不是把"笔记好了但搜不到"的状态宣告成完成（D-069 / AC-10）。
     *
     * <p>本方法不抛异常：它跑在分析生命周期回调与恢复扫描里，抛出去会打断 AI 主链或整轮扫描。
     * 构建失败（模型/向量库/数据库抖动）一律按"还没就绪"处理，下一轮重试——代价是完成晚一点，
     * 而不是任务状态被异常带偏。
     */
    public boolean ensureIndexed(Long mediaId) {
        try {
            if (isIndexed(mediaId)) {
                return true;
            }
            VideoContext context = loadContextQuietly(mediaId);
            if (context == null) {
                return false;
            }
            return !ensureChunks(mediaId, context.segments()).chunks().isEmpty();
        } catch (RuntimeException e) {
            log.warn("media_index_ensure_failed mediaId={}", mediaId, e);
            return false;
        }
    }

    private List<VideoChunk> loadChunksQuietly(Long mediaId) {
        try {
            return checkpointService.loadChunks(mediaId);
        } catch (RuntimeException e) {
            log.warn("media_index_chunks_read_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    private VideoContext loadContextQuietly(Long mediaId) {
        try {
            return checkpointService.loadContext(mediaId);
        } catch (RuntimeException e) {
            log.warn("media_index_context_read_failed mediaId={}", mediaId, e);
            return null;
        }
    }

    /** {@code reused=true} 表示命中已有分块快照，没有重新切分与重新计算向量。 */
    public record ChunkSnapshot(List<VideoChunk> chunks, boolean reused) {
    }
}
