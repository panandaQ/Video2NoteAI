package com.example.server.service.knowledge;

/**
 * 检索基础设施不可用的受控信号（runbook §10 / D-086）。
 *
 * <p>与“检索完成但没有任何证据命中”严格区分：前者必须收敛为
 * {@code FAILED/RETRIEVAL_UNAVAILABLE}，后者才是正常的 {@code MODEL_KNOWLEDGE}。
 * 禁止把基础设施故障伪装成“视频里没有答案”。
 */
public class KnowledgeRetrievalUnavailableException extends RuntimeException {

    public KnowledgeRetrievalUnavailableException(String message) {
        super(message);
    }

    public KnowledgeRetrievalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
