package com.example.server.dto.knowledge;

/**
 * 参与追问消歧的一轮历史（runbook §2.4：历史只用于消歧，不作为事实证据）。
 *
 * <p>来自 {@code knowledge_turns} 中最近若干 {@code COMPLETED} 轮次的受控投影：
 * 只有轮次号、问题与回答，不含证据与诊断字段。历史只是帮助模型理解本轮指代，
 * 来源模式不进入历史投影——上一轮是否由视频支撑与本轮消歧无关。
 */
public record HistoryTurn(
        int turnNo,
        String question,
        String answer
) {
    public HistoryTurn {
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
    }
}
