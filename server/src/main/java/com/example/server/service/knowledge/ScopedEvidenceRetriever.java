package com.example.server.service.knowledge;

import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.QueryPlan;

import java.util.List;

/**
 * 按授权范围检索证据的端口（runbook §9）。
 *
 * <p>首期只有 {@code SingleVideoEvidenceRetriever}（强制 {@code mediaIds.size()==1}）；
 * {@code LIBRARY} 跨视频归并属于 Q2，当前实现必须稳定拒绝而不是静默退化为单视频。
 *
 * <p>拆分 {@link #plan} 与 {@link #retrieve}（D-111）：查询规划是一次模型调用，其产出
 * （消歧后的独立问法 + 检索意图）同时被「检索」和「回答」两处消费，所以必须由调用方持有
 * 规划结果，而不是让检索方法内部藏一次模型调用再丢弃掉一半产出。
 */
public interface ScopedEvidenceRetriever {

    /** 查询规划：把问题与历史整理成检索意图（含消歧后的独立问法）。 */
    QueryPlan plan(String question, List<HistoryTurn> history);

    /** 按规划结果在授权范围内检索证据。 */
    RetrievalResult retrieve(RetrievalScope scope, QueryPlan plan);
}
