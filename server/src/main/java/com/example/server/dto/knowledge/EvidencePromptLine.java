package com.example.server.dto.knowledge;

/**
 * 进入回答 Prompt 的一条候选证据（runbook §6.2 第 6 步：服务端分配稳定短 ID）。
 *
 * <p>**双通道（D-100）**：{@code transcript} 是"说了什么"（平台字幕 CC 或 ASR 兜底），
 * {@code visualText} 是"画面写了什么"（关键帧 OCR）。两路都原样交给模型，因为答案可能只在其中
 * 一路：实测 media 66 问「这篇论文的作者都是谁」时，8 个作者名只存在于 {@code visualText}，
 * 口播只说"这里面有八个作者 作者绝大部分都是在google"——只给一路必然拒答。
 *
 * <p>**不再截断**：旧的 180 字上限会把作者名单/表格/公式切断（同一实测现场），现在只折叠空白。
 * {@code visualText} 是 OCR 结果、可能含识别误差，由 Prompt 侧的说明提示模型谨慎使用。
 *
 * <p>只携带编号、时间范围、来源与两路文本；不携带向量或内部资产 ID。
 */
public record EvidencePromptLine(
        String id,
        long startMs,
        long endMs,
        String source,
        String transcript,
        String visualText
) {
    public EvidencePromptLine {
        id = id == null ? "" : id;
        source = source == null ? "" : source;
        transcript = transcript == null ? "" : transcript;
        visualText = visualText == null ? "" : visualText;
    }
}
