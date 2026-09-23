package com.example.server.dto.knowledge;

import java.util.List;

/**
 * 证据回答的模型结构化输出（runbook §6.2 三模式）。
 *
 * <p>模型按来源诚实拆分回答：{@code videoAnswer} 只含视频证据支持的内容（用 {@code [En]}
 * 标注），{@code modelSupplement} 只含模型内部知识（不得携带 {@code [Ex]} 编号或时间戳）。
 * 最终 Markdown 由服务端组装，模型不负责排版。
 *
 * <p>{@code answerMode} 是字符串枚举名（"VIDEO_GROUNDED" / "HYBRID" / "MODEL_KNOWLEDGE"），
 * 由服务端 {@link AnswerMode#parse(String)} 解析；未知值兜底为 {@code MODEL_KNOWLEDGE}。
 */
public record GroundedAnswerResult(
        String answerMode,
        String videoAnswer,
        String modelSupplement,
        List<String> citedEvidenceIds,
        String sourceNotice
) {
    public GroundedAnswerResult {
        answerMode = answerMode == null ? "" : answerMode.trim();
        videoAnswer = videoAnswer == null ? "" : videoAnswer.trim();
        modelSupplement = modelSupplement == null ? "" : modelSupplement.trim();
        citedEvidenceIds = citedEvidenceIds == null ? List.of() : List.copyOf(citedEvidenceIds);
        sourceNotice = sourceNotice == null ? "" : sourceNotice.trim();
    }
}
