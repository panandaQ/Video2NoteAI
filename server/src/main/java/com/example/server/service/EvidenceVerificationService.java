package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidenceVerificationService {

    public boolean timestampCovered(VideoContext context, AnalysisResult.Evidence evidence) {
        return context != null && evidence != null && context.segments().stream()
                .anyMatch(segment -> containsTimestamp(segment, evidence.timestampMs()));
    }

    /**
     * 笔记证据校验：**只要内容确实来自该时间戳所在片段的转录（CC/ASR）或画面文字（OCR）就通过**。
     *
     * <p>不按 {@code source} 标签设卡（用户 2026-09-22 明确口径："准入依据有 CC 或者 ASR 就行了，
     * OCR 不一定非要"）：纯口播视频可能一条 OCR 都没有，而字幕优先后默认来源就是 {@code CC}。
     * 旧实现要求标签里出现 {@code ASR}/{@code OCR}，于是把全部 {@code CC} 证据判成不可核验——
     * media 66 实测：20 条证据全部出局、20 条结论全部无支撑、Critic 白跑一轮后带警告收尾（D-099）。
     *
     * <p>{@code source} 因此只作展示/审计字段，真伪由内容与片段的文本匹配判定（与问答引用
     * {@link #supported(VideoContext, VideoEvidenceHit)} 同一判据）。
     */
    public boolean supported(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return false;
        return context.segments().stream()
                .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
                .anyMatch(segment -> matchesAnyProbe(evidence.content(), segment));
    }

    /**
     * 按片段实际拥有的通道回填证据来源标签：{@code CC}/{@code ASR}/{@code CC+OCR}/{@code ASR+OCR}/{@code OCR}。
     *
     * <p>口径与问答链路的 {@code VideoEvidenceRetrievalService.toHit} **完全一致**：标签描述的是
     * "该片段有哪些通道"，而不是"这条正文具体来自哪一条"（正文可能只匹配 OCR，但同一片段同时有
     * 转录时就写 {@code CC+OCR}）。模型的 {@code source} 自填值不可信（media 66 实测：20 条全填
     * {@code CC}，其中 17 条正文逐字来自 {@code ocrTexts} 的幻灯片文本），所以这里在产物落库前
     * 用代码重算，让两条链路口径一致，也让"这条证据用了字幕还是画面文字"可审计。
     *
     * @return 校正后的标签；内容无法在该片段核验时返回 {@code null}（调用方保留原值）
     */
    public String resolveSource(VideoContext context, AnalysisResult.Evidence evidence) {
        if (context == null || evidence == null || evidence.content().isBlank()) return null;
        return context.segments().stream()
                .filter(segment -> containsTimestamp(segment, evidence.timestampMs()))
                .filter(segment -> matchesAnyProbe(evidence.content(), segment))
                .findFirst()
                .map(this::labelFor)
                .orElse(null);
    }

    private String labelFor(VideoContext.VideoSegment segment) {
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = segment.ocrTexts().stream().anyMatch(text -> !normalize(text).isEmpty());
        String transcriptLabel = segment.source() == TranscriptSource.CC ? "CC" : "ASR";
        if (hasTranscript && hasOcr) return transcriptLabel + "+OCR";
        return hasOcr ? "OCR" : transcriptLabel;
    }

    /** 内容是否出现在该片段的转录、单条 OCR 或拼接 OCR 之中（与问答引用重载同一判据）。 */
    private boolean matchesAnyProbe(String content, VideoContext.VideoSegment segment) {
        if (textMatches(content, segment.transcript())) return true;
        if (!segment.ocrTexts().isEmpty() && textMatches(content, String.join(" ", segment.ocrTexts()))) {
            return true;
        }
        return segment.ocrTexts().stream().anyMatch(text -> textMatches(content, text));
    }

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");

    public boolean supportsClaim(VideoContext context,
                                 String claim,
                                 AnalysisResult.Evidence evidence) {
        return evidence != null
                && !normalize(claim).isEmpty()
                && normalize(claim).equals(normalize(evidence.claim()))
                && supported(context, evidence)
                && containsClaimedNumbers(claim, evidence.content());
    }

    /**
     * 结论里出现的具体数字，必须真的写在证据正文里（media 66 反例）：
     * 28.4 BLEU 与 41.8 BLEU 两条结论曾被同一段 240000ms 摘要引言同时背书——引言确实是
     * 真实转录（{@link #supported} 通过），但引言里根本没有这两个数字中的任何一个。
     * 只比对 claim 标签文本相等，抓不住"证据是真的但答非所问"这种情况，因此在数字层面
     * 再加一道颗粒度校验：结论没有数字时不作强制，避免误杀定性结论。
     */
    private boolean containsClaimedNumbers(String claim, String evidenceContent) {
        List<String> numbers = extractNumbers(claim);
        if (numbers.isEmpty()) return true;
        String normalizedContent = normalize(evidenceContent);
        return numbers.stream().allMatch(number -> normalizedContent.contains(normalize(number)));
    }

    private List<String> extractNumbers(String text) {
        if (text == null) return List.of();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        List<String> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    /**
     * 问答引用校验（runbook §6.2 第 7 步）：候选证据命中在回答落库前仍要回原 Context 核验。
     *
     * <p>判定：命中起点必须落在某个原始片段内，且命中文本（snippet 或完整转录之一）
     * 能在该片段的转录、单个 OCR 文本或拼接 OCR 文本中归一化匹配。三种候选文本缺一不可——
     * 检索服务的 snippet 在画面文字占优时取的是<b>拼接后的 OCR 文本</b>，只对单个 OCR 文本做
     * contains 会误拒真实引用（真实链路实测 5 条引用只有 1 条通过，根因即此）。
     * 命中是检索派生物，不是授权凭证——这一层校验保证落库的引用真的来自本视频的 V2 Context。
     */
    public boolean supported(VideoContext context, VideoEvidenceHit hit) {
        if (context == null || hit == null) {
            return false;
        }
        return context.segments().stream()
                .filter(segment -> hit.startMs() >= segment.startMs() && hit.startMs() < segment.endMs())
                .anyMatch(segment -> matchesAnyProbe(hit, segment));
    }

    private boolean matchesAnyProbe(VideoEvidenceHit hit, VideoContext.VideoSegment segment) {
        String joinedOcr = segment.ocrTexts().isEmpty()
                ? ""
                : String.join(" ", segment.ocrTexts());
        for (String probe : new String[]{hit.snippet(), hit.transcript()}) {
            if (normalize(probe).isEmpty()) {
                continue;
            }
            if (textMatches(probe, segment.transcript()) || textMatches(probe, joinedOcr)) {
                return true;
            }
            for (String ocr : segment.ocrTexts()) {
                if (textMatches(probe, ocr)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsTimestamp(VideoContext.VideoSegment segment, long timestampMs) {
        return timestampMs >= segment.startMs() && timestampMs < segment.endMs();
    }

    private boolean textMatches(String evidence, String candidate) {
        String normalizedEvidence = normalize(evidence);
        String normalizedCandidate = normalize(candidate);
        return !normalizedEvidence.isEmpty()
                && !normalizedCandidate.isEmpty()
                && normalizedCandidate.contains(normalizedEvidence);
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }
}
