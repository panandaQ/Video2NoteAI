package com.example.server.dto;

/**
 * 共享内容资产的状态（规格 §7.1）。
 *
 * <p>它表达的是"这份内容当前有没有可用的字节版本"，与用户条目的状态分开：
 * 一个内容资产的 {@code READY} 意味着任何用户都可以直接复用它的对象引用而无需下载。
 */
public enum ContentAssetStatus {

    /** 尚无可用字节：可能是首个用户正在下载，也可能之前下载失败了。 */
    PROCESSING,

    /** 已有可用字节版本（{@code object_ref} 非空）。 */
    READY,

    /** 获取被判定为确定性失败，等待下一次有用户重新触发获取。 */
    FAILED
}
