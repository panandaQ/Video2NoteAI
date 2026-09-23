package com.example.server.dto.knowledge;

import java.util.Set;

/**
 * 问答轮次状态（runbook §4.2）。
 *
 * <p>{@link #PROCESSING} 不得被当作成功：只有回答与证据在同一事务中持久化后才能进入
 * {@link #COMPLETED}。终态（COMPLETED/FAILED）由完成/失败短事务在条件更新中写入，
 * 旧执行线程的条件更新必然为 0 行，结果直接丢弃。
 */
public enum KnowledgeTurnStatus {

    PROCESSING,
    COMPLETED,
    FAILED;

    private static final Set<KnowledgeTurnStatus> TERMINAL = Set.of(COMPLETED, FAILED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
