package com.example.server.dto;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * URL 导入父任务的业务状态。
 *
 * <p>父任务聚合一个或多个媒体单元，因此它与媒体状态是两套独立枚举：父任务回答“这次提交整体怎么样”，
 * 媒体状态回答“这个可播放单元处理到哪一步”。禁止把两者互相赋值。
 *
 * <p>状态转换只允许 {@link #canTransitionTo(VideoImportJobStatus)} 中声明的方向，禁止从较新位置退回较旧位置。
 */
public enum VideoImportJobStatus {

    PENDING_DISPATCH,
    QUEUED,
    RESOLVING,
    PROCESSING,
    COMPLETED,
    DISPATCH_FAILED,
    PARTIAL_SUCCESS,
    FAILED;

    private static final Map<VideoImportJobStatus, Set<VideoImportJobStatus>> ALLOWED =
            new EnumMap<>(VideoImportJobStatus.class);

    static {
        ALLOWED.put(PENDING_DISPATCH, EnumSet.of(QUEUED, DISPATCH_FAILED));
        ALLOWED.put(QUEUED, EnumSet.of(RESOLVING, FAILED));
        ALLOWED.put(RESOLVING, EnumSet.of(PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED));
        ALLOWED.put(PROCESSING, EnumSet.of(COMPLETED, PARTIAL_SUCCESS, FAILED));
        // 重投权由数据库条件更新给出；可重试判定由服务层结合 retryable 字段决定。
        ALLOWED.put(DISPATCH_FAILED, EnumSet.of(PENDING_DISPATCH));
        ALLOWED.put(FAILED, EnumSet.of(PENDING_DISPATCH));
        ALLOWED.put(PARTIAL_SUCCESS, EnumSet.of(PROCESSING));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(VideoImportJobStatus.class));
    }

    public boolean canTransitionTo(VideoImportJobStatus next) {
        return next != null && ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    /** 终态：进入后必须清空 {@code active_request_key}，允许相同 URL 再次提交。 */
    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL_SUCCESS || this == FAILED;
    }

    /** 是否处于可重投递的异常态；具体重投仍需服务层确认 {@code retryable}。 */
    public boolean isRetryEntry() {
        return this == DISPATCH_FAILED || this == FAILED;
    }
}
