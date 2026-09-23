package com.example.server.dto.knowledge;

import jakarta.validation.constraints.NotNull;

/**
 * 首问的会话范围（runbook §5.1）：{@code SINGLE_VIDEO} 必须携带 mediaId；
 * {@code LIBRARY} 当前切片稳定拒绝（422 {@code KNOWLEDGE_SCOPE_NOT_SUPPORTED}）。
 */
public record KnowledgeScopeRequest(
        @NotNull(message = "scope.type 不能为空")
        KnowledgeScopeType type,

        Long mediaId
) {
}
