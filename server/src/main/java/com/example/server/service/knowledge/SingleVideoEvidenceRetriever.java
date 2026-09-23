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
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link ScopedEvidenceRetriever} 的单视频实现（runbook §9）。
 *
 * <p>复用现有混合检索：加载 V2 Chunk（D-079：只读 V2 快照，不回退 V1），再走
 * {@link VideoEvidenceRetrievalService#search} 的向量/关键词/OCR 融合与降级。
 *
 * <p>强制 {@code mediaIds.size()==1}；{@code LIBRARY} 返回 {@code KNOWLEDGE_SCOPE_NOT_SUPPORTED}，
 * 不静默退化。分块快照缺失对 READY 媒体而言是内容级异常（D-069 保证 READY 必有 V2 快照），
 * 因此按 {@link KnowledgeRetrievalUnavailableException} 处理而不是“无证据”。
 */
@Service
public class SingleVideoEvidenceRetriever implements ScopedEvidenceRetriever {

    /** 诊断字段：单视频检索模式名。 */
    static final String MODE_HYBRID = "HYBRID";

    private final AgentCheckpointService checkpointService;
    private final VideoEvidenceRetrievalService retrievalService;

    public SingleVideoEvidenceRetriever(AgentCheckpointService checkpointService,
                                        VideoEvidenceRetrievalService retrievalService) {
        this.checkpointService = checkpointService;
        this.retrievalService = retrievalService;
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
        List<VideoChunk> chunks = checkpointService.loadChunks(mediaId);
        if (chunks == null || chunks.isEmpty()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "媒体分块快照缺失: mediaId=" + mediaId);
        }
        List<VideoEvidenceHit> hits = retrievalService.search(mediaId, plan, chunks);
        return new RetrievalResult(hits, MODE_HYBRID, hits.size());
    }
}
