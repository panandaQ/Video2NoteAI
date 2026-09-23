package com.example.server.infrastructure;

/**
 * B 站 web API 返回的业务错误。
 *
 * <p>只承载平台错误码与文案；到契约错误码的翻译由来源 Adapter 完成，基础设施层不感知导入语义。
 */
public class BilibiliApiException extends RuntimeException {

    /** 平台业务码：0 表示成功，负数为通用错误，正数为稿件级错误。 */
    public static final int NOT_FOUND = -404;
    public static final int ACCESS_DENIED = -403;
    public static final int INVALID_REQUEST = -400;
    public static final int VIDEO_NOT_VISIBLE = 62002;
    public static final int VIDEO_UNDER_REVIEW = 62004;

    private final int code;

    public BilibiliApiException(int code, String message) {
        super("B 站接口返回 " + code + ": " + message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
