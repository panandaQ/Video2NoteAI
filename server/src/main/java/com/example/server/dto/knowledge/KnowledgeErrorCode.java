package com.example.server.dto.knowledge;

/**
 * 知识问答的错误码（runbook §10）。
 *
 * <p>两类使用方式：
 * <ul>
 *   <li>同步受理错误：服务抛出 {@code BusinessException}，message 以 {@link #code()} 开头，
 *       前端按 code 识别（HTTP 状态由各场景决定）；</li>
 *   <li>异步轮次失败：{@link #code()} 写入 {@code knowledge_turns.error_code}，回答失败后
 *       用户以新 requestId 在原会话重新生成。</li>
 * </ul>
 *
 * <p>与 {@code VideoImportErrorCode} 的取舍一致：异步错误码只保存可控短文案，不保存模型输出、
 * Prompt 或检索原始响应。
 */
public enum KnowledgeErrorCode {

    /** 媒体存在且属于当前用户，但尚未 READY（runbook §10：409）。 */
    MEDIA_NOT_READY("MEDIA_NOT_READY", "视频内容尚未就绪，暂时无法提问"),
    /** 继续会话时携带的 scope 与 MySQL 固定范围不一致（D-082）。 */
    CONVERSATION_SCOPE_MISMATCH("CONVERSATION_SCOPE_MISMATCH", "会话的视频范围不能改变，请创建新会话"),
    /** 同会话已有另一个请求正在处理（MySQL CAS 未命中）。 */
    CONVERSATION_BUSY("CONVERSATION_BUSY", "该会话正在处理另一个问题，请稍候"),
    /** 当前切片只支持单视频问答；LIBRARY 范围稳定拒绝。 */
    KNOWLEDGE_SCOPE_NOT_SUPPORTED("KNOWLEDGE_SCOPE_NOT_SUPPORTED", "当前只支持单视频问答"),
    /** 有界执行器拒绝：立即条件更新为 FAILED 并释放执行权（runbook §6.1）。 */
    QUESTION_QUEUE_FULL("QUESTION_QUEUE_FULL", "问答队列已满，请稍后重试"),
    /** 所有检索方式都不可用：受控服务错误，不能伪装成“没有答案”（D-086）。 */
    RETRIEVAL_UNAVAILABLE("RETRIEVAL_UNAVAILABLE", "检索服务暂时不可用，请稍后重试"),
    /** 模型调用失败或输出无法解析。 */
    ANSWER_GENERATION_FAILED("ANSWER_GENERATION_FAILED", "回答生成失败，请重新提问"),
    /** 进程中断：僵尸轮次由超时收敛为可重试失败（runbook §6.4）。 */
    QUESTION_PROCESS_INTERRUPTED("QUESTION_PROCESS_INTERRUPTED", "回答处理被中断，请重新提问");

    private final String code;
    private final String message;

    KnowledgeErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 同步错误的安全文案：code 前缀便于前端与测试稳定识别。 */
    public String messageWithCode() {
        return code + "：" + message;
    }
}
