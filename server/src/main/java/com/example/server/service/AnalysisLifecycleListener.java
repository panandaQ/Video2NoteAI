package com.example.server.service;

import com.example.server.dto.AnalysisMode;

/**
 * 分析任务生命周期的观察者。
 *
 * <p>现有分析消费者只负责 AI 主链；导入模块需要在默认视频笔记开始、完成和失败时推进媒体与父任务状态。
 * 与其让消费者反向依赖导入模块，这里由调用方（消费者）定义最小接口，导入模块提供实现，
 * 依赖方向保持不变。
 *
 * <p>实现必须自行判断是否与自己相关（例如按 {@code goalDigest} 过滤默认笔记身份），
 * 消费者不对观察者做业务过滤。
 */
public interface AnalysisLifecycleListener {

    /** 消费者取得执行权、任务真正开始处理。 */
    default void onStarted(Long mediaId, String goal, AnalysisMode mode) {
    }

    /**
     * 分析结果已经持久化，任务成功结束（含复用已有结果）。
     *
     * @param verified Critic 是否通过（{@code false} 表示达到最大轮次仍未通过、带警告收尾——
     *                 D-104：结果依然是"完成"，观察者不得把这两种情况混为一谈静默清除警告痕迹）
     */
    default void onCompleted(Long mediaId, String goal, AnalysisMode mode, boolean verified) {
    }

    /** 本次执行失败但会重投，任务仍在进行中。 */
    default void onRetryableFailure(Long mediaId, String goal, AnalysisMode mode) {
    }

    /** 确定性失败或投递次数耗尽，任务终止。 */
    default void onPermanentFailure(Long mediaId, String goal, AnalysisMode mode) {
    }
}
