package com.example.server.source;

/**
 * 一次 URL 提交的识别结果。
 *
 * <p>父任务创建时是 {@link #DETECTING}，只有 Adapter 解析完成后才能变成 {@link #SINGLE} 或
 * {@link #COLLECTION}。请求线程不得猜测类型，类型只能由服务端 Adapter 给出。
 */
public enum ImportTargetType {

    /** 尚未解析；父任务创建初值。 */
    DETECTING,
    /** 单个可播放单元。 */
    SINGLE,
    /** 多分 P 稿件或公开合集，包含多个可播放单元。 */
    COLLECTION;

    public boolean isResolved() {
        return this != DETECTING;
    }
}
