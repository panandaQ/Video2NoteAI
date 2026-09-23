package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOptions;
import com.example.server.dto.knowledge.EvidencePromptLine;
import com.example.server.dto.knowledge.GroundedAnswerResult;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.service.EvidenceVerificationService;
import com.example.server.utils.DeepSeekUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查询改写与证据约束回答（runbook §6.2 三模式）：Prompt 构造、模型调用、引用 ID 校验、
 * 引用二次校验与最终 Markdown 组装。
 *
 * <p>模型与服务的分工固定：
 * <ul>
 *   <li>服务端给候选证据分配稳定短 ID（{@code E1..En}，上限 {@code knowledge.retrieval.max-evidence}）；</li>
 *   <li>模型按来源输出 {@code answerMode/videoAnswer/modelSupplement/citedEvidenceIds}，未知 ID 一律拒绝；</li>
 *   <li>引用还要经 {@link EvidenceVerificationService} 回原 Context 二次校验；</li>
 *   <li>最终 Markdown 由服务端组装，来源提示文案硬编码固定，模型知识绝不伪装成视频内容。</li>
 * </ul>
 *
 * <p>候选为空不再固定拒答：调模型产出 {@code MODEL_KNOWLEDGE}（视频优先、模型知识兜底）。
 */
@Service
public class EvidenceGroundedAnswerService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceGroundedAnswerService.class);

    private static final Locale ID_NORMALIZE_LOCALE = Locale.ROOT;

    /** MODEL_KNOWLEDGE 的固定来源提示；措辞必须是"未检索到"，不得断言"视频中没有提到"。 */
    static final String MODEL_KNOWLEDGE_NOTICE =
            "> 本次未在视频中检索到直接依据，以下回答来自模型通用知识。";

    /** HYBRID 的固定来源提示。 */
    static final String HYBRID_NOTICE = "> 模型补充部分未在本轮视频证据中检索到。";

    private final DeepSeekUtils deepSeekUtils;
    private final EvidenceVerificationService verificationService;
    private final KnowledgeQuestionProperties properties;

    public EvidenceGroundedAnswerService(DeepSeekUtils deepSeekUtils,
                                         EvidenceVerificationService verificationService,
                                         KnowledgeQuestionProperties properties) {
        this.deepSeekUtils = deepSeekUtils;
        this.verificationService = verificationService;
        this.properties = properties;
    }

    /**
     * 回答结果：来源模式 + 是否找到校验通过的视频引用 + 组装好的答案 + 落库引用 + 引用诊断。
     *
     * <p>{@code rawCitationCount}/{@code fabricatedCitationCount} 供评测计算伪造引用率：
     * 模型声称的引用总数与其中无效（未知证据 ID）的数量，不参与落库。
     */
    public record GroundedResult(
            AnswerMode answerMode,
            boolean videoEvidenceFound,
            String answer,
            List<VideoEvidenceHit> citedEvidence,
            int rawCitationCount,
            int fabricatedCitationCount
    ) {
        public GroundedResult {
            citedEvidence = citedEvidence == null ? List.of() : List.copyOf(citedEvidence);
        }
    }

    /**
     * 证据回答：分配编号 → 模型调用 → 引用 ID 校验 → Context 二次校验 → 按来源组装 Markdown。
     *
     * @param question  本轮用于作答的问法。追问场景必须传**消歧后的独立问法**（D-108）。
     * @param history   最近历史轮次，只用于理解指代；Prompt 里显式声明不是事实依据（runbook §2.4）。
     * @param candidates 本轮检索命中的候选证据（可能为空：空时走 MODEL_KNOWLEDGE，不拒答）
     * @param context    该媒体的 V2 Context，用于引用二次校验
     * @param options    消融开关：{@code groundWithEvidence} 决定是否启用编号约束与校验
     */
    public GroundedResult answer(String question,
                                 List<HistoryTurn> history,
                                 VideoContext context,
                                 List<VideoEvidenceHit> candidates,
                                 AnswerOptions options) {
        List<HistoryTurn> safeHistory = history == null ? List.of() : List.copyOf(history);
        if (!options.groundWithEvidence()) {
            // 消融 A/B/C：证据只作参考，模型自由作答；不落任何"已验证引用"。
            GroundedAnswerResult raw = deepSeekUtils.answerWithEvidence(
                    question, safeHistory, promptLines(candidates), false);
            AnswerMode mode = AnswerMode.parse(raw.answerMode());
            return new GroundedResult(mode, false, assemble(mode, raw), List.of(),
                    raw.citedEvidenceIds().size(), 0);
        }

        // 候选为空：不拒答，调模型产出 MODEL_KNOWLEDGE（视频优先、模型知识兜底）。
        if (candidates == null || candidates.isEmpty()) {
            GroundedAnswerResult raw = deepSeekUtils.answerWithEvidence(
                    question, safeHistory, List.of(), true);
            int rawCount = raw.citedEvidenceIds().size();
            return new GroundedResult(AnswerMode.MODEL_KNOWLEDGE, false,
                    assemble(AnswerMode.MODEL_KNOWLEDGE, raw), List.of(), rawCount, rawCount);
        }

        List<VideoEvidenceHit> limited = candidates.size() > properties.getRetrieval().getMaxEvidence()
                ? candidates.subList(0, properties.getRetrieval().getMaxEvidence())
                : candidates;
        List<EvidencePromptLine> lines = promptLines(limited);

        GroundedAnswerResult raw = deepSeekUtils.answerWithEvidence(question, safeHistory, lines, true);
        AnswerMode mode = AnswerMode.parse(raw.answerMode());
        CitationResolution resolution = resolveCitations(raw.citedEvidenceIds(), limited);
        List<VideoEvidenceHit> cited = resolution.cited();
        int fabricated = resolution.fabricated();
        int rawCount = raw.citedEvidenceIds().size();

        List<VideoEvidenceHit> verified = options.verifyCitations()
                ? cited.stream().filter(hit -> verificationService.supported(context, hit)).toList()
                : cited;

        // 可观测性（runbook §15）：只记录来源模式、引用 ID 与校验数，不记录回答正文。
        log.info("knowledge_answer_generated mode={} rawCitations={} fabricated={} cited={} verified={}",
                mode, rawCount, fabricated, cited.size(), verified.size());

        if (mode == AnswerMode.MODEL_KNOWLEDGE) {
            // 模型自己判断无有效证据：不落引用，模型知识回答。MODEL_KNOWLEDGE 声称零视频依据，
            // 任何声称的引用 ID 都视为伪造引用，不进入校验。
            return new GroundedResult(AnswerMode.MODEL_KNOWLEDGE, false,
                    assemble(AnswerMode.MODEL_KNOWLEDGE, raw), List.of(), rawCount, rawCount);
        }

        if (!verified.isEmpty()) {
            // 有有效引用：按模型来源模式组装（VIDEO_GROUNDED 或 HYBRID）。
            return new GroundedResult(mode, true, assemble(mode, raw), verified, rawCount, fabricated);
        }

        // 模型声称有视频依据但引用全被拒：兜底重调一次，生成 MODEL_KNOWLEDGE。
        GroundedAnswerResult raw2 = deepSeekUtils.answerWithEvidence(
                question, safeHistory, List.of(), true);
        int rawCount2 = raw2.citedEvidenceIds().size();
        return new GroundedResult(AnswerMode.MODEL_KNOWLEDGE, false,
                assemble(AnswerMode.MODEL_KNOWLEDGE, raw2), List.of(), rawCount2, rawCount2);
    }

    /** 按来源模式组装最终 Markdown；来源提示文案由服务端固定，不采用模型 sourceNotice 措辞。 */
    private String assemble(AnswerMode mode, GroundedAnswerResult raw) {
        return switch (mode) {
            case VIDEO_GROUNDED -> blankToEmpty(raw.videoAnswer());
            case HYBRID -> assembleHybrid(raw);
            case MODEL_KNOWLEDGE -> MODEL_KNOWLEDGE_NOTICE + "\n\n" + blankToEmpty(raw.modelSupplement());
        };
    }

    /** HYBRID 分开展示「视频中可以确认」与「模型补充」，模型知识部分不带 [En] 编号与时间戳。 */
    private String assembleHybrid(GroundedAnswerResult raw) {
        return "**视频中可以确认**\n" + blankToEmpty(raw.videoAnswer())
                + "\n\n**模型补充**\n" + blankToEmpty(raw.modelSupplement())
                + "\n\n" + HYBRID_NOTICE;
    }

    private String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    /**
     * 引用 ID 解析：只接受服务端分配的编号，未知 ID 一律拒绝并计为伪造引用（runbook §6.2），
     * 按编号顺序映射回原始证据。
     */
    private CitationResolution resolveCitations(List<String> rawIds, List<VideoEvidenceHit> limited) {
        Set<String> assigned = limitedIds(limited);
        List<VideoEvidenceHit> cited = new ArrayList<>(limited.size());
        int fabricated = 0;
        for (String id : rawIds) {
            if (id == null || !assigned.contains(id.trim().toUpperCase(ID_NORMALIZE_LOCALE))) {
                fabricated++;
                continue;
            }
            int index = parseRank(id);
            if (index < 0 || index >= limited.size()) {
                fabricated++;
                continue;
            }
            cited.add(limited.get(index));
        }
        return new CitationResolution(cited, fabricated);
    }

    private record CitationResolution(List<VideoEvidenceHit> cited, int fabricated) {
    }

    private Set<String> limitedIds(List<VideoEvidenceHit> limited) {
        return java.util.stream.IntStream.range(0, limited.size())
                .mapToObj(EvidenceGroundedAnswerService::evidenceId)
                .collect(Collectors.toSet());
    }

    private int parseRank(String id) {
        try {
            return Integer.parseInt(id.trim().substring(1)) - 1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 组装回答 Prompt 的证据行（D-100 双通道）：
     * {@code transcript} = 语音/字幕原文，{@code visualText} = 画面文字（关键帧 OCR）原文。
     */
    private List<EvidencePromptLine> promptLines(List<VideoEvidenceHit> candidates) {
        List<EvidencePromptLine> lines = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            VideoEvidenceHit hit = candidates.get(i);
            lines.add(new EvidencePromptLine(
                    evidenceId(i), hit.startMs(), hit.endMs(), hit.source(),
                    hit.transcript(),
                    String.join(" ", hit.ocrTexts())));
        }
        return lines;
    }

    /** 稳定短 ID：E1 起，与候选顺序一一对应。 */
    static String evidenceId(int index) {
        return "E" + (index + 1);
    }
}
