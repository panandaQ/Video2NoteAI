package com.example.server.dto.knowledge;

/**
 * 一轮问答的引用证据（runbook §5.3）：只暴露当前用户条目的 mediaId、
 * 标题快照、时间范围、来源与有界片段；不暴露其他用户或共享资产内部 ID。
 */
public record KnowledgeEvidenceResponse(
        int rank,
        Long mediaId,
        String title,
        long startMs,
        long endMs,
        String source,
        String snippet,
        Double score
) {
}
