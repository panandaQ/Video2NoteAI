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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证据回答契约（runbook §6.2 三模式）：候选为空走 MODEL_KNOWLEDGE 而非拒答、有效引用走
 * VIDEO_GROUNDED、部分证据走 HYBRID 分区、未知 ID 拒绝并计数伪造、校验失败兜底 MODEL_KNOWLEDGE、
 * 模型知识不伪造 Evidence ID、历史透传、消融档自由作答。
 */
class EvidenceGroundedAnswerServiceTest {

    private static final String QUESTION = "第二种方案有什么代价？";

    private final DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
    private final EvidenceVerificationService verificationService = mock(EvidenceVerificationService.class);
    private final EvidenceGroundedAnswerService service =
            new EvidenceGroundedAnswerService(deepSeekUtils, verificationService, new KnowledgeQuestionProperties());

    private final VideoContext context = new VideoContext("lesson.mp4", "目标", List.of());

    @Test
    void noCandidatesReturnsModelKnowledgeInsteadOfRefusal() {
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("MODEL_KNOWLEDGE", "", "这是模型知识回答", List.of(), ""));

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, List.of(), AnswerOptions.D);

        assertEquals(AnswerMode.MODEL_KNOWLEDGE, result.answerMode());
        assertFalse(result.videoEvidenceFound());
        assertTrue(result.answer().startsWith("> 本次未在视频中检索到直接依据"),
                "来源提示必须在答案开头，且措辞不得断言'视频中没有提到'");
        assertTrue(result.answer().contains("这是模型知识回答"));
        assertTrue(result.citedEvidence().isEmpty());
        verify(deepSeekUtils).answerWithEvidence(eq(QUESTION), anyList(), eq(List.of()), eq(true));
    }

    @Test
    void validCitationsProduceVideoGrounded() {
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("VIDEO_GROUNDED", "答案是 [E1]", "", List.of("E1"), ""));
        when(verificationService.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(true);

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(3), AnswerOptions.D);

        assertEquals(AnswerMode.VIDEO_GROUNDED, result.answerMode());
        assertTrue(result.videoEvidenceFound());
        assertEquals("答案是 [E1]", result.answer());
        assertEquals(List.of(1L), result.citedEvidence().stream().map(VideoEvidenceHit::startMs).toList());
        assertEquals(1, result.rawCitationCount());
        assertEquals(0, result.fabricatedCitationCount());
    }

    @Test
    void hybridSeparatesVideoAnswerAndModelSupplement() {
        when(deepSeekUtils.answerWithEvidence(anyString(), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("HYBRID", "视频可确认 [E1]", "模型补充内容", List.of("E1"), ""));
        when(verificationService.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(true);

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(2), AnswerOptions.D);

        assertEquals(AnswerMode.HYBRID, result.answerMode());
        assertTrue(result.videoEvidenceFound());
        assertTrue(result.answer().contains("**视频中可以确认**"));
        assertTrue(result.answer().contains("视频可确认 [E1]"));
        assertTrue(result.answer().contains("**模型补充**"));
        assertTrue(result.answer().contains("模型补充内容"));
        assertTrue(result.answer().contains("> 模型补充部分未在本轮视频证据中检索到。"));
    }

    @Test
    void rejectsUnknownEvidenceIdsAndCountsFabrications() {
        when(deepSeekUtils.answerWithEvidence(anyString(), anyList(), anyList(), anyBoolean()))
                .thenReturn(new GroundedAnswerResult("VIDEO_GROUNDED", "答案", "", List.of("E1", "E9", "e3", "乱码"), ""));
        when(verificationService.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(true);

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(3), AnswerOptions.D);

        assertEquals(List.of(1L, 3L), result.citedEvidence().stream()
                .map(VideoEvidenceHit::startMs).toList());
        assertEquals(4, result.rawCitationCount());
        assertEquals(2, result.fabricatedCitationCount(), "E9 与乱码是未分配的伪造引用");
    }

    @Test
    void modelKnowledgeNeverFabricatesEvidenceIds() {
        when(deepSeekUtils.answerWithEvidence(anyString(), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("MODEL_KNOWLEDGE", "", "模型知识回答", List.of("E1"), ""));

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(2), AnswerOptions.D);

        assertEquals(AnswerMode.MODEL_KNOWLEDGE, result.answerMode());
        assertTrue(result.citedEvidence().isEmpty(), "MODEL_KNOWLEDGE 必须零视频引用");
        assertEquals(1, result.fabricatedCitationCount());
    }

    @Test
    void citationsFailingVerificationFallBackToModelKnowledge() {
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("VIDEO_GROUNDED", "答案", "", List.of("E1"), ""));
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), eq(List.of()), eq(true)))
                .thenReturn(new GroundedAnswerResult("MODEL_KNOWLEDGE", "", "兜底回答", List.of(), ""));
        when(verificationService.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(false);

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(2), AnswerOptions.D);

        assertEquals(AnswerMode.MODEL_KNOWLEDGE, result.answerMode(), "引用全被拒时降级为模型知识，不拒答");
        assertFalse(result.videoEvidenceFound());
        assertTrue(result.answer().contains("兜底回答"));
        assertTrue(result.citedEvidence().isEmpty());
        verify(deepSeekUtils).answerWithEvidence(eq(QUESTION), anyList(), eq(List.of()), eq(true));
    }

    @Test
    void ungroundedModeSkipsIdValidationAndVerificationAndPersistsNothing() {
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(false)))
                .thenReturn(new GroundedAnswerResult("VIDEO_GROUNDED", "自由回答", "", List.of("E1"), ""));

        EvidenceGroundedAnswerService.GroundedResult result =
                service.answer(QUESTION, List.of(), context, hits(2), AnswerOptions.C);

        assertFalse(result.videoEvidenceFound());
        assertEquals("自由回答", result.answer());
        assertTrue(result.citedEvidence().isEmpty());
        verify(verificationService, never()).supported(any(VideoContext.class), any(VideoEvidenceHit.class));
    }

    /**
     * D-100 双通道 + 取消 180 字上限：转录与画面文字都原样进 Prompt。
     */
    @Test
    void promptCarriesBothChannelsWithoutTruncation() {
        String longTranscript = "这是口播字幕内容，".repeat(40); // 480 字，远超旧的 180 上限
        String slide = "Attention Is All You Need / Ashish Vaswani* Noam Shazeer* Niki Parmar*";
        VideoEvidenceHit hit = new VideoEvidenceHit(
                0L, 60_000L, "CC", slide, longTranscript, List.of(slide, "Google Brain"));
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(true)))
                .thenReturn(new GroundedAnswerResult("VIDEO_GROUNDED", "答案", "", List.of("E1"), ""));
        when(verificationService.supported(any(VideoContext.class), any(VideoEvidenceHit.class))).thenReturn(true);

        service.answer(QUESTION, List.of(), context, List.of(hit), AnswerOptions.D);

        ArgumentCaptor<List<EvidencePromptLine>> prompt = ArgumentCaptor.forClass(List.class);
        verify(deepSeekUtils).answerWithEvidence(eq(QUESTION), anyList(), prompt.capture(), eq(true));
        EvidencePromptLine line = prompt.getValue().get(0);
        assertEquals(longTranscript, line.transcript(), "转录通道必须完整、不截断");
        assertEquals(slide + " Google Brain", line.visualText(), "画面文字通道必须进 Prompt");
        assertEquals("CC", line.source());
        assertEquals(0L, line.startMs());
        assertEquals(60_000L, line.endMs());
    }

    /**
     * D-108：历史必须透传到回答 Prompt，作为指代消歧依据（runbook §2.4），但不作为事实证据。
     */
    @Test
    void historyIsForwardedToPromptForDisambiguation() {
        List<HistoryTurn> history = List.of(
                new HistoryTurn(1, "直接插入排序和冒泡排序的复杂度？", "都是 O(n²)，稳定。"),
                new HistoryTurn(2, "那下一种呢？", "上一轮的回答"));
        when(deepSeekUtils.answerWithEvidence(eq(QUESTION), anyList(), anyList(), eq(false)))
                .thenReturn(new GroundedAnswerResult("MODEL_KNOWLEDGE", "", "自由回答", List.of(), ""));

        service.answer(QUESTION, history, context, hits(2), AnswerOptions.B);

        ArgumentCaptor<List<HistoryTurn>> historyArg = ArgumentCaptor.forClass(List.class);
        verify(deepSeekUtils).answerWithEvidence(eq(QUESTION), historyArg.capture(), anyList(), eq(false));
        assertEquals(history, historyArg.getValue(), "历史必须原样透传到回答模型");
    }

    private List<VideoEvidenceHit> hits(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new VideoEvidenceHit(1L + i, 10L + i, "ASR", "片段" + (i + 1), "", List.of()))
                .toList();
    }
}
