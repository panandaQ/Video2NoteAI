package com.example.server.dto.knowledge;

/**
 * 问答执行链的消融开关（runbook §9 / §14.3）。
 *
 * <p>四个布尔值直接对应评测的 A/B/C/D 四档配置：消融只是本参数的取值差异，
 * 不允许为消融复制第二份执行链。
 * <ul>
 *   <li>{@code includeHistory}：是否把最近历史拼进上下文；</li>
 *   <li>{@code rewriteQuery}：追问时是否调用查询改写（首问恒用原问题）；</li>
 *   <li>{@code groundWithEvidence}：回答是否受证据约束（只引用服务端分配的 E1..En，
 *       无有效引用强制拒答）；</li>
 *   <li>{@code verifyCitations}：引用的证据是否回原 Context 二次校验。</li>
 * </ul>
 */
public record AnswerOptions(
        boolean includeHistory,
        boolean rewriteQuery,
        boolean groundWithEvidence,
        boolean verifyCitations
) {

    /** A：原问题、无历史、无证据约束。 */
    public static final AnswerOptions A = new AnswerOptions(false, false, false, false);

    /** B：拼最近历史、不改写、无证据约束。 */
    public static final AnswerOptions B = new AnswerOptions(true, false, false, false);

    /** C：历史 + 改写 + 现有混合检索，无证据约束。 */
    public static final AnswerOptions C = new AnswerOptions(true, true, false, false);

    /** D（默认）：在 C 上增加证据约束回答与服务端校验（生产配置）。 */
    public static final AnswerOptions D = new AnswerOptions(true, true, true, true);
}
