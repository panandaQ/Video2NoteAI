package com.example.server.dto.knowledge;

/**
 * 会话生命周期状态。
 *
 * <p>会话 CAS 只接受 {@link #ACTIVE} 会话取得执行权（runbook §2.2 的 {@code status = 'ACTIVE'}
 * 条件）；{@link #ARCHIVED} 为会话生命周期预留，本切片没有归档入口。
 */
public enum KnowledgeConversationStatus {

    ACTIVE,
    ARCHIVED
}
