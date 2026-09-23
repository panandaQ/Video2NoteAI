package com.example.server.source;

import com.example.server.dto.VideoImportErrorCode;

/**
 * 来源适配失败的统一异常。
 *
 * <p>Adapter 只能抛出本异常（或它的子类）表达平台侧失败，并携带契约 §11 的错误码。
 * 导入模块据此决定重投、失败台账或永久失败，不需要解析异常文案。
 */
public class VideoSourceException extends RuntimeException {

    private final VideoImportErrorCode errorCode;

    public VideoSourceException(VideoImportErrorCode errorCode) {
        this(errorCode, errorCode.message(), null);
    }

    public VideoSourceException(VideoImportErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public VideoSourceException(VideoImportErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public VideoImportErrorCode errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return errorCode.retryable();
    }
}
