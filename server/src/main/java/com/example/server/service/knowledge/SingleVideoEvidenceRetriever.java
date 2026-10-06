package com.example.server.service.knowledge;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.KnowledgeScopeType;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.exception.BusinessException;
import com.example.server.common.ErrorCode;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.VideoEvidenceRetrievalService;
import com.example.server.config.MinuteRagProperties;
import com.example.server.service.MinuteRagRetrievalService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * {@link ScopedEvidenceRetriever} 的单视频实现（runbook §9）。
 *
 * <p>生产默认走分钟级 RAG：从 V2 Context 构建分钟文档，再走 BM25 + BGE-M3 + RRF +
 * Reranker。分钟上下文未就绪时明确返回检索不可用，避免把追问静默降级到旧的 5 分钟
 * Chunk 粒度。旧 Chunk 检索仅作为显式关闭分钟开关后的回滚路径保留。
 *
 * <p>强制 {@code mediaIds.size()==1}；{@code LIBRARY} 返回 {@code KNOWLEDGE_SCOPE_NOT_SUPPORTED}，
 * 不静默退化。分块快照缺失对 READY 媒体而言是内容级异常（D-069 保证 READY 必有 V2 快照），
 * 因此按 {@link KnowledgeRetrievalUnavailableException} 处理而不是“无证据”。
 */
@Service
public class SingleVideoEvidenceRetriever implements ScopedEvidenceRetriever {

    /** 诊断字段：单视频检索模式名。 */
    static final String MODE_HYBRID = "HYBRID";
    static final String MODE_MINUTE_RAG = "MINUTE_RAG";

    private final AgentCheckpointService checkpointService;
    private final VideoEvidenceRetrievalService retrievalService;
    private final MinuteRagRetrievalService minuteRetrievalService;
    private final MinuteRagProperties minuteRagProperties;

    public SingleVideoEvidenceRetriever(AgentCheckpointService checkpointService,
                                        VideoEvidenceRetrievalService retrievalService) {
        this(checkpointService, retrievalService, null, null);
    }

    @Autowired
    public SingleVideoEvidenceRetriever(AgentCheckpointService checkpointService,
                                        VideoEvidenceRetrievalService retrievalService,
                                        MinuteRagRetrievalService minuteRetrievalService,
                                        MinuteRagProperties minuteRagProperties) {
        this.checkpointService = checkpointService;
        this.retrievalService = retrievalService;
        this.minuteRetrievalService = minuteRetrievalService;
        this.minuteRagProperties = minuteRagProperties;
    }

    @Override
    public QueryPlan plan(String question, List<HistoryTurn> history) {
        return retrievalService.planQuery(question, history);
    }

    @Override
    public RetrievalResult retrieve(RetrievalScope scope, QueryPlan plan) {
        if (scope.type() == KnowledgeScopeType.LIBRARY) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    KnowledgeErrorCode.KNOWLEDGE_SCOPE_NOT_SUPPORTED.messageWithCode());
        }
        if (scope.mediaIds().size() != 1) {
            throw new IllegalArgumentException("单视频检索只接受一个 mediaId，实际 " + scope.mediaIds().size());
        }
        Long mediaId = scope.mediaIds().get(0);
        if (minuteRagProperties != null && minuteRagProperties.isEnabled()) {
            if (minuteRetrievalService == null) {
                throw new KnowledgeRetrievalUnavailableException(
                        "分钟级检索服务未配置: mediaId=" + mediaId);
            }
            var context = checkpointService.loadContext(mediaId);
            if (context == null || context.segments().isEmpty()) {
                throw new KnowledgeRetrievalUnavailableException(
                        "分钟级上下文索引未就绪: mediaId=" + mediaId);
            }
            List<VideoEvidenceHit> hits = minuteRetrievalService.search(mediaId, plan);
            return new RetrievalResult(hits, MODE_MINUTE_RAG, hits.size());
        }
        List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
        if (chunks == null || chunks.isEmpty()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "媒体分块快照缺失: mediaId=" + mediaId);
        }
        List<VideoEvidenceHit> hits = retrievalService.search(mediaId, plan, chunks);
        return new RetrievalResult(hits, MODE_HYBRID, hits.size());
    }
}
