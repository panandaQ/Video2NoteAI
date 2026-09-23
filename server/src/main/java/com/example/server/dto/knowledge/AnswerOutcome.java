package com.example.server.dto.knowledge;

import com.example.server.entity.KnowledgeTurnEvidence;

import java.util.List;

/**
 * 问答执行链的产出（runbook §2.6）：改写查询 + 检索诊断 + 校验通过的引用证据 + 来源模式 + 回答。
 *
 * <p>{@code answerMode} 与 {@code videoEvidenceFound} 是来源维度的两个正交字段；{@code evidence}
 * 是已经过引用 ID 校验与 Context 二次校验、准备随轮次终态落库的引用行。
 *
 * <p>{@code rawCitationCount}/{@code fabricatedCitationCount} 是评测诊断：模型声称的引用数与其中
 * 无效（未知/不存在的证据 ID）的数量，供评测计算伪造引用率，不落库。{@code durationMs} 从执行
 * 开始到产出本结果的耗时。
 */
public record AnswerOutcome(
        String rewrittenQuery,
        String retrievalMode,
        int retrievedCount,
        List<KnowledgeTurnEvidence> evidence,
        AnswerMode answerMode,
        boolean videoEvidenceFound,
        String answer,
        long durationMs,
        int rawCitationCount,
        int fabricatedCitationCount
) {
    public AnswerOutcome {
        rewrittenQuery = rewrittenQuery == null ? "" : rewrittenQuery;
        retrievalMode = retrievalMode == null ? "" : retrievalMode;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        answer = answer == null ? "" : answer;
    }

    public int citedCount() {
        return evidence.size();
    }
}
