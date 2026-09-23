package com.example.server.dto.knowledge;

import java.util.List;

/**
 * 问答执行器的服务层入口入参（runbook §2.6 / §9）：不依赖 HTTP 请求上下文、SSE emitter 或事务边界。
 *
 * <p>{@code history} 由调用方（问答编排）从 MySQL 加载最近 {@code COMPLETED} 轮次；
 * 评测 Runner 与消融实验可以直接构造本对象，不必驱动整个 Web 栈。
 */
public record AnswerRequest(
        Long userId,
        Long mediaId,
        String question,
        List<HistoryTurn> history,
        AnswerOptions options
) {
    public AnswerRequest {
        question = question == null ? "" : question.trim();
        history = history == null ? List.of() : List.copyOf(history);
        options = options == null ? AnswerOptions.D : options;
    }
}
