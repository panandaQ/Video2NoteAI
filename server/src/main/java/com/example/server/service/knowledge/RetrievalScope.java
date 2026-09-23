package com.example.server.service.knowledge;

import com.example.server.dto.knowledge.KnowledgeScopeType;

import java.util.List;

/**
 * 一轮问答的授权检索范围（runbook §9）：由 MySQL 用户关系解析，客户端不能提交或覆盖。
 *
 * <p>{@code SINGLE_VIDEO} 恰好一个 mediaId；{@code LIBRARY} 是当前用户全部 READY 条目
 * （当前切片稳定拒绝）。范围只在单次检索内有效，下一轮必须重新解析。
 */
public record RetrievalScope(
        KnowledgeScopeType type,
        List<Long> mediaIds
) {
    public RetrievalScope {
        if (type == null) {
            throw new IllegalArgumentException("检索范围类型不能为空");
        }
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }

    public static RetrievalScope singleVideo(Long mediaId) {
        if (mediaId == null) {
            throw new IllegalArgumentException("单视频范围必须携带 mediaId");
        }
        return new RetrievalScope(KnowledgeScopeType.SINGLE_VIDEO, List.of(mediaId));
    }
}
