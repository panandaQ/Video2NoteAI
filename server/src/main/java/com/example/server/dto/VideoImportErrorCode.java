package com.example.server.dto;

/**
 * 导入链路的异步错误码（契约 §11）。
 *
 * <p>错误码同时决定可重试性，避免各处再写一套字符串判断。同步 HTTP 错误继续使用 {@link
 * com.example.server.common.ErrorCode}，两者职责不同：本枚举只写入父任务、子项或媒体记录。
 */
public enum VideoImportErrorCode {

    SOURCE_UNSUPPORTED(false, "当前来源不受支持"),
    /** D-067：多分 P 稿件必须由用户在链接上指明要哪一个分 P，服务端不猜也不展开。 */
    SOURCE_UNIT_REQUIRED(false, "该稿件包含多个分 P，请在链接上指定要导入的分 P"),
    SOURCE_NOT_FOUND(false, "视频或合集不存在"),
    SOURCE_ACCESS_DENIED(false, "视频无访问权限"),
    SOURCE_LOGIN_REQUIRED(false, "来源需要登录后才能枚举"),
    /** 强制登录：用户尚未保存 B 站 Cookie（异步解析阶段兜底，正常在受理阶段同步拦截）。 */
    BILIBILI_COOKIE_REQUIRED(false, "请先在设置页保存 B 站 Cookie 后再导入"),
    /** 强制登录：用户已保存 Cookie 但登录态已失效，需要去设置页更新。 */
    BILIBILI_COOKIE_EXPIRED(false, "B 站 Cookie 已过期，请在设置页更新后重新导入"),
    COLLECTION_TOO_LARGE(false, "合集单元数超过上限"),
    SOURCE_METADATA_INVALID(false, "来源元数据缺少稳定身份"),
    SOURCE_RATE_LIMITED(true, "来源限流或风控"),
    SOURCE_TEMPORARY_UNAVAILABLE(true, "来源暂时不可用"),
    ITEM_DISPATCH_FAILED(true, "单元获取消息投递失败"),
    MEDIA_ACQUIRE_FAILED(true, "媒体获取或写入对象存储失败"),
    NOTE_DISPATCH_DEFERRED(true, "后台笔记容量不足，等待延迟重投"),
    NOTE_PROCESSING_FAILED(true, "默认笔记处理临时失败"),
    NOTE_PROCESSING_REJECTED(false, "默认笔记遇到确定性错误"),
    /** Critic 达到最大重试轮次仍未通过：笔记正常进入 READY，仅作展示用的复核提示，不触发重投（D-104）。 */
    NOTE_UNVERIFIED_EVIDENCE(false, "笔记已生成，但部分结论未通过证据核验，建议人工复核"),
    ARTIFACT_ENRICHMENT_FAILED(true, "分析输入资产补充失败，等待重试"),
    MEDIA_DELETED(false, "处理中媒体已被用户删除"),
    INTERNAL_ERROR(true, "未分类内部错误");

    private final boolean retryable;
    private final String message;

    VideoImportErrorCode(boolean retryable, String message) {
        this.retryable = retryable;
        this.message = message;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 只保存可控短文案；禁止写入 yt-dlp 原始输出、Cookie、Token 或签名地址。 */
    public String message() {
        return message;
    }
}
