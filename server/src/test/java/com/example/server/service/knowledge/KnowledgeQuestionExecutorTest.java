package com.example.server.service.knowledge;

import com.example.server.config.KnowledgeQuestionProperties;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.knowledge.AnswerMode;
import com.example.server.dto.knowledge.AnswerOptions;
import com.example.server.dto.knowledge.AnswerOutcome;
import com.example.server.dto.knowledge.AnswerRequest;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.KnowledgeErrorCode;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.entity.MediaFile;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentExecutionBudget;
import com.example.server.service.AgentTelemetry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问答执行器契约（runbook §2.6 / §6.2）：服务层入口不依赖 HTTP/SSE；
 * 查询规划由调用方持有并同时供检索与回答消费（D-111）、上下文缺失是检索不可用、
 * 编排失败分流到受控错误码。
 */
class KnowledgeQuestionExecutorTest {

    private static final Long USER_ID = 7L;
    private static final Long MEDIA_ID = 27L;

    private final KnowledgeConversationQueryService queryService = mock(KnowledgeConversationQueryService.class);
    private final ScopedEvidenceRetriever retriever = mock(ScopedEvidenceRetriever.class);
    private final EvidenceGroundedAnswerService answerService = mock(EvidenceGroundedAnswerService.class);
    private final AgentCheckpointService checkpointService = mock(AgentCheckpointService.class);
    private final MediaFileMapper mediaFileMapper = mock(MediaFileMapper.class);
    private final KnowledgeQuestionCommandService commandService = mock(KnowledgeQuestionCommandService.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);

    private final KnowledgeQuestionExecutor executor = new KnowledgeQuestionExecutor(
            queryService, retriever, answerService, checkpointService, mediaFileMapper,
            new KnowledgeQuestionProperties(), commandService, telemetry);

    private final VideoContext context = new VideoContext("lesson.mp4", "目标", List.of());

    @Test
    void firstQuestionPlansWithEmptyHistoryAndAnswersWithOriginalQuestion() {
        stubMedia();
        stubPlan(new QueryPlan("视频中如何避免缓存击穿？", "缓存击穿 防护", List.of("缓存击穿"), List.of()));
        stubRetrieval(List.of());
        stubModelKnowledge();

        AnswerOutcome outcome = executor.answer(new AnswerRequest(
                USER_ID, MEDIA_ID, "视频中如何避免缓存击穿？", List.of(), AnswerOptions.D));

        assertEquals(AnswerMode.MODEL_KNOWLEDGE, outcome.answerMode());
        assertEquals(0, outcome.retrievedCount());
        verify(retriever).plan("视频中如何避免缓存击穿？", List.of());
        assertTrue(planHistoryCaptor().isEmpty(), "首问没有历史，规划不得携带历史");
    }

    /**
     * D-111：规划结果由执行器持有并同时喂给检索与回答。
     *
     * <p>合并前是「改写 → 再规划」两次串行调用，且改写产出只喂检索、回答拿的是原始指代问句
     * （评测实测因此出现误拒答："那下一种呢？" 模型猜不到指代对象而拒答）。
     */
    @Test
    void followUpPlanCarriesHistoryAndAnswerUsesStandaloneQuery() {
        List<HistoryTurn> history = List.of(
                new HistoryTurn(1, "直接插入排序和冒泡排序的复杂度？", "都是 O(n²)。"));
        stubMedia();
        stubPlan(new QueryPlan("选择排序的平均、最坏时间复杂度及稳定性怎样？",
                "选择排序 复杂度 稳定性", List.of("选择排序"), List.of()));
        stubRetrieval(List.of());
        stubModelKnowledge();

        executor.answer(new AnswerRequest(USER_ID, MEDIA_ID, "那下一种呢？", history, AnswerOptions.D));

        verify(retriever).plan("那下一种呢？", history);
        assertEquals(history, planHistoryCaptor(), "追问的规划必须携带历史用于消歧");

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        verify(answerService).answer(question.capture(), anyList(), any(), anyList(), any());
        assertEquals("选择排序的平均、最坏时间复杂度及稳定性怎样？", question.getValue(),
                "回答必须收到规划产出的独立问法，而不是未消歧的原始指代问句");
    }

    /** D-111：不启用改写的消融档（A/B）保持原问题作答——这是 A/B 消融的测量点。 */
    @Test
    void variantWithoutRewriteAnswersWithOriginalQuestionAndNoHistory() {
        List<HistoryTurn> history = List.of(new HistoryTurn(1, "上一轮问题", "上一轮回答"));
        stubMedia();
        stubPlan(new QueryPlan("独立问法", "检索语句", List.of("关键词"), List.of()));
        stubRetrieval(List.of());
        stubModelKnowledge();

        executor.answer(new AnswerRequest(USER_ID, MEDIA_ID, "原问题", history, AnswerOptions.A));

        assertTrue(planHistoryCaptor().isEmpty(), "A 档不携带历史，规划也不得看到历史");

        ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<HistoryTurn>> historyArg = ArgumentCaptor.forClass(List.class);
        verify(answerService).answer(question.capture(), historyArg.capture(), any(), anyList(), any());
        assertEquals("原问题", question.getValue(), "A 档必须用原问题作答");
        assertTrue(historyArg.getValue().isEmpty(), "A 档回答必须不携带历史");
    }

    @Test
    void buildsEvidenceRowsFromVerifiedCitations() {
        stubMedia();
        stubPlan(new QueryPlan("问题", "检索语句", List.of(), List.of()));
        stubRetrieval(List.of(hit(10_000, 20_000, "ASR", "片段一")));
        VideoEvidenceHit cited = hit(10_000, 20_000, "ASR", "片段一");
        when(answerService.answer(anyString(), anyList(), any(), anyList(), any()))
                .thenReturn(new EvidenceGroundedAnswerService.GroundedResult(
                        AnswerMode.VIDEO_GROUNDED, true, "回答", List.of(cited), 1, 0));

        AnswerOutcome outcome = executor.answer(new AnswerRequest(
                USER_ID, MEDIA_ID, "问题", List.of(), AnswerOptions.D));

        assertTrue(outcome.videoEvidenceFound());
        assertEquals(1, outcome.citedCount());
        assertEquals(10_000, outcome.evidence().get(0).getStartMs());
        assertEquals(20_000, outcome.evidence().get(0).getEndMs());
        assertEquals("ASR", outcome.evidence().get(0).getSource());
        assertEquals("缓存专题", outcome.evidence().get(0).getTitleSnapshot());
        assertEquals(MEDIA_ID, outcome.evidence().get(0).getMediaId());
        assertEquals(1, outcome.evidence().get(0).getEvidenceRank());
        assertTrue(outcome.durationMs() >= 0);
    }

    @Test
    void missingContextIsRetrievalUnavailableNotNoAnswer() {
        when(checkpointService.loadContext(MEDIA_ID)).thenReturn(null);
        stubPlan(new QueryPlan("问题", "检索语句", List.of(), List.of()));
        stubRetrieval(List.of(hit(1, 2, "ASR", "x")));

        assertThrows(KnowledgeRetrievalUnavailableException.class, () -> executor.answer(new AnswerRequest(
                USER_ID, MEDIA_ID, "问题", List.of(), AnswerOptions.D)));
        verify(answerService, never()).answer(anyString(), anyList(), any(), anyList(), any());
    }

    // ---- 编排：失败分流与旧线程丢弃 ----

    @Test
    void processCompletesTurnFromOutcome() {
        stubProcess();
        stubRetrieval(List.of());
        stubModelKnowledge();
        when(commandService.complete(any())).thenReturn(true);

        executor.process(new KnowledgeQuestionExecutor.ProcessCommand(
                USER_ID, 91L, 314L, "req-1", 0L, MEDIA_ID, "问题", "trace-1"));

        ArgumentCaptor<KnowledgeQuestionCommandService.CompletionInput> completion =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.CompletionInput.class);
        verify(commandService).complete(completion.capture());
        assertEquals(314L, completion.getValue().turnId());
        assertEquals(AnswerMode.MODEL_KNOWLEDGE, completion.getValue().answerMode());
        verify(commandService, never()).fail(any());
    }

    @Test
    void processMapsRetrievalUnavailableToControlledFailure() {
        when(queryService.loadHistory(91L, USER_ID, 0L)).thenReturn(List.of());
        stubPlan(new QueryPlan("问题", "检索语句", List.of(), List.of()));
        when(retriever.retrieve(any(), any(QueryPlan.class)))
                .thenThrow(new KnowledgeRetrievalUnavailableException("分块缺失"));

        executor.process(new KnowledgeQuestionExecutor.ProcessCommand(
                USER_ID, 91L, 314L, "req-1", 0L, MEDIA_ID, "问题", "trace-1"));

        ArgumentCaptor<KnowledgeQuestionCommandService.FailureInput> failure =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.FailureInput.class);
        verify(commandService).fail(failure.capture());
        assertEquals(KnowledgeErrorCode.RETRIEVAL_UNAVAILABLE, failure.getValue().errorCode());
        verify(commandService, never()).complete(any());
    }

    @Test
    void processMapsRuntimeFailureAndDeadlineToAnswerGenerationFailed() {
        when(queryService.loadHistory(91L, USER_ID, 0L)).thenReturn(List.of());
        stubPlan(new QueryPlan("问题", "检索语句", List.of(), List.of()));
        when(retriever.retrieve(any(), any(QueryPlan.class)))
                .thenThrow(new AgentExecutionBudget.DeadlineExceededException("预算耗尽"));

        executor.process(new KnowledgeQuestionExecutor.ProcessCommand(
                USER_ID, 91L, 314L, "req-1", 0L, MEDIA_ID, "问题", "trace-1"));

        ArgumentCaptor<KnowledgeQuestionCommandService.FailureInput> failure =
                ArgumentCaptor.forClass(KnowledgeQuestionCommandService.FailureInput.class);
        verify(commandService).fail(failure.capture());
        assertEquals(KnowledgeErrorCode.ANSWER_GENERATION_FAILED, failure.getValue().errorCode());
    }

    @Test
    void processDiscardsSilentlyWhenTurnWasAlreadyConverged() {
        stubProcess();
        stubRetrieval(List.of());
        stubModelKnowledge();
        when(commandService.complete(any())).thenReturn(false); // 旧线程：僵尸收敛已抢先

        executor.process(new KnowledgeQuestionExecutor.ProcessCommand(
                USER_ID, 91L, 314L, "req-1", 0L, MEDIA_ID, "问题", "trace-1"));

        verify(commandService).complete(any());
        verify(commandService, never()).fail(any());
    }

    // ---- 桩 ----

    private void stubMedia() {
        when(checkpointService.loadContext(MEDIA_ID)).thenReturn(context);
        when(mediaFileMapper.selectById(MEDIA_ID)).thenReturn(mediaWithTitle("缓存专题"));
    }

    private void stubProcess() {
        when(queryService.loadHistory(91L, USER_ID, 0L)).thenReturn(List.of());
        stubMedia();
        stubPlan(new QueryPlan("问题", "检索语句", List.of(), List.of()));
    }

    private void stubPlan(QueryPlan plan) {
        when(retriever.plan(anyString(), anyList())).thenReturn(plan);
    }

    private void stubRetrieval(List<VideoEvidenceHit> hits) {
        when(retriever.retrieve(any(), any(QueryPlan.class)))
                .thenReturn(new RetrievalResult(hits, "HYBRID", hits.size()));
    }

    private void stubModelKnowledge() {
        when(answerService.answer(anyString(), anyList(), any(), anyList(), any()))
                .thenReturn(new EvidenceGroundedAnswerService.GroundedResult(
                        AnswerMode.MODEL_KNOWLEDGE, false, "回答", List.of(), 0, 0));
    }

    private List<HistoryTurn> planHistoryCaptor() {
        ArgumentCaptor<List<HistoryTurn>> captor = ArgumentCaptor.forClass(List.class);
        verify(retriever).plan(anyString(), captor.capture());
        return captor.getValue();
    }

    private MediaFile mediaWithTitle(String title) {
        MediaFile media = new MediaFile();
        media.setId(MEDIA_ID);
        media.setUserId(USER_ID);
        media.setSourceTitle(title);
        return media;
    }

    private VideoEvidenceHit hit(long start, long end, String source, String snippet) {
        return new VideoEvidenceHit(start, end, source, snippet, snippet, List.of());
    }
}
