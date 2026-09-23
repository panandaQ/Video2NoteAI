package com.example.server.dto.knowledge;

/**
 * 会话的检索范围类型（D-082：会话范围创建后不可变）。
 *
 * <p>本切片只实现 {@link #SINGLE_VIDEO}；{@link #LIBRARY} 保留为稳定模型边界（V7 的 CHECK 约束
 * 也承认它），但任何阶段都不提前实现跨视频检索——提交 LIBRARY 范围时稳定拒绝。
 */
public enum KnowledgeScopeType {

    /** 固定绑定一个当前用户拥有且 READY 的 mediaId。 */
    SINGLE_VIDEO,

    /** 每轮重新解析当前用户全部 READY 条目；当前切片不支持。 */
    LIBRARY
}
