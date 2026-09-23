package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceVerificationServiceTest {

    private final EvidenceVerificationService service = new EvidenceVerificationService();
    private final VideoContext context = new VideoContext(
            "lesson.mp4",
            "总结课程",
            List.of(new VideoContext.VideoSegment(
                    120_000,
                    180_000,
                    "接下来讲解二叉树的前序遍历",
                    List.of("前序遍历：根节点、左子树、右子树"),
                    List.of("frame_000125.jpg"))));

    @Test
    void acceptsVerbatimEvidenceAtTheDeclaredTimestamp() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supported(context, evidence));
        assertTrue(service.supportsClaim(context, "前序遍历顺序", evidence));
    }

    @Test
    void rejectsTextThatOnlyLooksSimilarToTheSource() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点左子树不存在，因此应跳过", "前序遍历顺序");

        assertFalse(service.supported(context, evidence));
    }

    // ---- 准入判据（D-099）：有转录（CC/ASR）就够，OCR 不是必需 ----

    /** 纯口播片段（一条 OCR 都没有）在字幕优先后是最常见形态：CC 证据必须通过。 */
    @Test
    void acceptsCcEvidenceOnSegmentWithoutAnyOcr() {
        VideoContext ccOnly = new VideoContext("lesson.mp4", "总结课程", List.of(
                new VideoContext.VideoSegment(120_000, 180_000, "接下来讲解二叉树的前序遍历",
                        List.of(), List.of(), TranscriptSource.CC, null)));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "CC", "接下来讲解二叉树的前序遍历", "前序遍历顺序");

        assertTrue(service.supported(ccOnly, evidence));
        assertEquals("CC", service.resolveSource(ccOnly, evidence));
    }

    /** 纯 ASR 兜底（无字幕、无画面文字）同样只靠转录通过。 */
    @Test
    void acceptsAsrEvidenceOnSegmentWithoutAnyOcr() {
        VideoContext asrOnly = new VideoContext("lesson.mp4", "总结课程", List.of(
                new VideoContext.VideoSegment(120_000, 180_000, "接下来讲解二叉树的前序遍历",
                        List.of(), List.of(), TranscriptSource.ASR, null)));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "ASR", "接下来讲解二叉树的前序遍历", "前序遍历顺序");

        assertTrue(service.supported(asrOnly, evidence));
        assertEquals("ASR", service.resolveSource(asrOnly, evidence));
    }

    /**
     * media 66 的真实形态：正文逐字来自 OCR（幻灯片），模型却把 source 填成 CC。
     *
     * <p>旧准入判据要求标签含 ASR/OCR，纯 CC 直接出局 → 20 条证据全判不可核验、Critic 白跑一轮。
     * 现在按内容核验通过；标签按片段通道重算（该片段同时有转录与画面文字）→ {@code CC+OCR}，
     * 与问答链路 {@code toHit} 的口径一致。
     */
    @Test
    void acceptsOcrContentMislabelledAsCcAndCorrectsTheLabel() {
        VideoContext ccWithOcr = new VideoContext("lesson.mp4", "总结课程", List.of(
                new VideoContext.VideoSegment(120_000, 180_000, "接下来讲解二叉树的前序遍历",
                        List.of("前序遍历：根节点、左子树、右子树"), List.of(), TranscriptSource.CC, null)));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "CC", "前序遍历：根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supported(ccWithOcr, evidence));
        assertEquals("CC+OCR", service.resolveSource(ccWithOcr, evidence));
    }

    /** 只有画面文字、没有转录的片段：标签校正为 OCR。 */
    @Test
    void correctsLabelToOcrWhenOnlyVisualTextMatches() {
        VideoContext ocrOnly = new VideoContext("lesson.mp4", "总结课程", List.of(
                new VideoContext.VideoSegment(120_000, 180_000, "",
                        List.of("前序遍历：根节点、左子树、右子树"), List.of(), TranscriptSource.CC, null)));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "CC", "前序遍历：根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supported(ocrOnly, evidence));
        assertEquals("OCR", service.resolveSource(ocrOnly, evidence));
    }

    /** 编造内容不管标签怎么写都不通过，是这条链路的正确性底线。 */
    @Test
    void rejectsFabricatedContentRegardlessOfLabel() {
        for (String label : List.of("CC", "ASR", "OCR", "CC+OCR", "ASR+OCR")) {
            AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                    125_000, label, "视频说二叉树不需要遍历", "前序遍历顺序");
            assertFalse(service.supported(context, evidence), "label=" + label);
            assertNull(service.resolveSource(context, evidence), "label=" + label);
        }
    }

    // ---- 事实颗粒度校验（media 66 反例）：同一段落不能背书两条不同的具体数字结论 ----

    /**
     * media 66 实测：28.4 BLEU 与 41.8 BLEU 两条结论绑定了同一段 240000ms 摘要引言，
     * 引言本身确实来自真实转录（第一层 {@code supported} 会通过），但引言前 400 字里
     * 根本没出现这两个数字中的任何一个。旧的 {@code supportsClaim} 只比对 claim 标签是否
     * 文本相等，不检查证据内容是否真的含有结论主张的具体数字，因此两条互斥的结论会被
     * 同一份"真实但不相关"的引用同时放行。
     */
    @Test
    void rejectsClaimWhenEvidenceContentOmitsTheSpecificNumberAsserted() {
        VideoContext paper = new VideoContext("paper.mp4", "总结论文", List.of(
                new VideoContext.VideoSegment(240_000, 300_000,
                        "我们的模型在多个翻译任务上取得了当前最优的结果",
                        List.of(), List.of())));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                240_000, "CC", "我们的模型在多个翻译任务上取得了当前最优的结果",
                "在WMT 2014英德翻译上达到28.4 BLEU。");

        assertTrue(service.supported(paper, evidence), "引用本身是真实转录，第一层校验应通过");
        assertFalse(service.supportsClaim(paper, "在WMT 2014英德翻译上达到28.4 BLEU。", evidence),
                "证据内容没有出现结论主张的具体数字，不能算支撑该结论");
    }

    @Test
    void acceptsClaimWhenEvidenceContentIncludesTheAssertedNumber() {
        VideoContext paper = new VideoContext("paper.mp4", "总结论文", List.of(
                new VideoContext.VideoSegment(240_000, 300_000,
                        "我们的模型在WMT 2014英德翻译任务上达到28.4的BLEU分数",
                        List.of(), List.of())));
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                240_000, "CC", "我们的模型在WMT 2014英德翻译任务上达到28.4的BLEU分数",
                "在WMT 2014英德翻译上达到28.4 BLEU。");

        assertTrue(service.supportsClaim(paper, "在WMT 2014英德翻译上达到28.4 BLEU。", evidence));
    }

    /** 结论不含数字时（定性描述）不做数字颗粒度强制，避免误杀正常结论。 */
    @Test
    void doesNotEnforceNumberGroundingForNonNumericClaims() {
        AnalysisResult.Evidence evidence = new AnalysisResult.Evidence(
                125_000, "OCR", "根节点、左子树、右子树", "前序遍历顺序");

        assertTrue(service.supportsClaim(context, "前序遍历顺序", evidence));
    }

    // ---- 问答引用（VideoEvidenceHit）重载：runbook §6.2 第 7 步的落库前二次校验 ----

    @Test
    void hitInsideSegmentWithMatchingSnippetIsSupported() {
        VideoEvidenceHit hit = new VideoEvidenceHit(
                130_000, 150_000, "ASR", "讲解二叉树的前序遍历", "接下来讲解二叉树的前序遍历", List.of());

        assertTrue(service.supported(context, hit));
    }

    @Test
    void hitMatchingOcrTextIsSupported() {
        VideoEvidenceHit hit = new VideoEvidenceHit(
                125_000, 135_000, "OCR", "根节点、左子树、右子树", "", List.of());

        assertTrue(service.supported(context, hit));
    }

    @Test
    void hitSnippetWithJoinedOcrTextIsSupported() {
        // 检索服务在画面文字占优时把片段的多条 OCR 拼成一段作为 snippet，
        // 校验必须对拼接后的 OCR 文本做匹配（真实链路实测暴露：只对单条 OCR 匹配会误拒）。
        VideoContext joinedContext = new VideoContext(
                "lesson.mp4", "总结课程",
                List.of(new VideoContext.VideoSegment(
                        120_000, 180_000, "",
                        List.of("根节点、", "左子树、右子树"),
                        List.of())));
        VideoEvidenceHit hit = new VideoEvidenceHit(
                130_000, 150_000, "OCR", "根节点、 左子树、右子树", "", List.of());

        assertTrue(service.supported(joinedContext, hit));
    }

    @Test
    void hitOutsideAnySegmentIsRejected() {
        VideoEvidenceHit hit = new VideoEvidenceHit(
                10_000, 20_000, "ASR", "讲解二叉树的前序遍历", "", List.of());

        assertFalse(service.supported(context, hit));
    }

    @Test
    void hitWithFabricatedTextInsideSegmentIsRejected() {
        VideoEvidenceHit hit = new VideoEvidenceHit(
                130_000, 150_000, "ASR", "视频说不要学习任何算法", "", List.of());

        assertFalse(service.supported(context, hit));
    }

    @Test
    void nullInputsAreRejected() {
        assertFalse(service.supported(null, new VideoEvidenceHit(0, 1, "ASR", "x", "", List.of())));
        assertFalse(service.supported(context, (VideoEvidenceHit) null));
    }
}
