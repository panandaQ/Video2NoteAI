package com.example.server.service;

import com.example.server.config.NoteBatchProperties;
import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.NoteInputBatch;
import com.example.server.dto.SegmentPart;
import com.example.server.dto.VideoContext;
import com.example.server.service.ingest.VideoNoteProfile;
import com.example.server.utils.DeepSeekUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AgentLoopNoteBatchTest {

    @Test
    void defaultNoteExecutesEachBatchAndAggregatesInSequenceOrder() {
        DeepSeekUtils deepSeek = mock(DeepSeekUtils.class);
        LongVideoContextService contextService = mock(LongVideoContextService.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        EvidenceVerificationService evidence = mock(EvidenceVerificationService.class);
        TaskEventService events = mock(TaskEventService.class);
        AgentBudgetService budget = mock(AgentBudgetService.class);
        NoteBatchBuilder builder = new NoteBatchBuilder(properties());
        Map<String, com.example.server.dto.NoteBatchResult> savedBatches = new HashMap<>();

        when(budget.settings()).thenReturn(new com.example.server.service.AgentBudgetEstimator.Settings(
                2, 2, 120_000, 120_000, 100_000, 100_000, 1, 1));
        when(budget.maxEstimatedCost()).thenReturn(0D);
        when(telemetry.currentUsage()).thenReturn(new AgentTelemetry.BudgetUsage(0, 0D));
        when(budget.estimate(any())).thenReturn(new com.example.server.service.AgentBudgetEstimator.Estimate(
                100_000, 120_000, 4, 2, 10));
        when(checkpoints.loadCriticState(anyLong(), anyString(), any())).thenReturn(null);
        when(checkpoints.loadPlan(anyLong(), anyString(), any())).thenReturn(null);
        when(checkpoints.loadNoteBatchResult(anyLong(), eq(VideoNoteProfile.VERSION), anyString()))
                .thenAnswer(invocation -> savedBatches.get(invocation.getArgument(2)));
        doAnswer(invocation -> {
            com.example.server.dto.NoteBatchResult saved = invocation.getArgument(3);
            savedBatches.put(saved.batchId(), saved);
            return null;
        }).when(checkpoints).saveNoteBatchResult(anyLong(), eq(VideoNoteProfile.VERSION), any(), any());
        when(deepSeek.plan(any(VideoContext.class), anyString())).thenReturn(
                new AgentState.AgentPlan("goal", List.of("cover all batches")));
        when(deepSeek.execute(any(VideoContext.class), any(), any(), anyString()))
                .thenReturn(result("batch"));
        when(deepSeek.aggregate(any(), any(), anyList(), anyString())).thenReturn(result("aggregate"));
        when(deepSeek.critique(any(), any(), any(), anyString())).thenReturn(
                new AgentState.CriticResult(false, List.of("批次 2 需要补证据"), List.of(),
                        List.of(), List.of(15L)),
                new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()));
        when(evidence.resolveSource(any(), any())).thenReturn("CC");
        when(evidence.supported(any(), any(AnalysisResult.Evidence.class))).thenReturn(true);
        when(evidence.supportsClaim(any(), any(), any(AnalysisResult.Evidence.class))).thenReturn(true);

        AgentLoopService service = new AgentLoopService(
                deepSeek, contextService, checkpoints, telemetry, evidence, events, budget, builder);
        VideoContext context = new VideoContext("source", VideoNoteProfile.GOAL, List.of(
                new VideoContext.VideoSegment(0, 10, "first", List.of(), List.of()),
                new VideoContext.VideoSegment(10, 20, "second", List.of(), List.of())));

        service.run(7L, context, null);

        verify(deepSeek, times(3)).execute(any(VideoContext.class), any(), any(), anyString());
        verify(deepSeek, times(2)).aggregate(any(), any(), argThat(results -> results.size() == 2), anyString());
        verify(checkpoints, times(3)).saveNoteBatchResult(anyLong(), eq(VideoNoteProfile.VERSION), any(), any());
    }

    @Test
    void singleBatchSkipsAggregatorCall() {
        DeepSeekUtils deepSeek = mock(DeepSeekUtils.class);
        AgentCheckpointService checkpoints = mock(AgentCheckpointService.class);
        AgentTelemetry telemetry = mock(AgentTelemetry.class);
        EvidenceVerificationService evidence = mock(EvidenceVerificationService.class);
        TaskEventService events = mock(TaskEventService.class);
        AgentBudgetService budget = mock(AgentBudgetService.class);
        when(budget.settings()).thenReturn(new com.example.server.service.AgentBudgetEstimator.Settings(
                2, 2, 120_000, 120_000, 100_000, 100_000, 1, 1));
        when(budget.maxEstimatedCost()).thenReturn(0D);
        when(telemetry.currentUsage()).thenReturn(new AgentTelemetry.BudgetUsage(0, 0D));
        when(budget.estimate(any())).thenReturn(new com.example.server.service.AgentBudgetEstimator.Estimate(
                100_000, 120_000, 3, 1, 10));
        when(checkpoints.loadCriticState(anyLong(), anyString(), any())).thenReturn(null);
        when(deepSeek.plan(any(VideoContext.class), anyString()))
                .thenReturn(new AgentState.AgentPlan("goal", List.of("cover content")));
        when(deepSeek.execute(any(VideoContext.class), any(), any(), anyString()))
                .thenReturn(result("single"));
        when(deepSeek.critique(any(), any(), any(), anyString()))
                .thenReturn(new AgentState.CriticResult(true, List.of(), List.of(), List.of(), List.of()));
        when(evidence.resolveSource(any(), any())).thenReturn("CC");
        when(evidence.supported(any(), any(AnalysisResult.Evidence.class))).thenReturn(true);
        when(evidence.supportsClaim(any(), any(), any(AnalysisResult.Evidence.class))).thenReturn(true);

        AgentLoopService service = new AgentLoopService(
                deepSeek, mock(LongVideoContextService.class), checkpoints, telemetry,
                evidence, events, budget, new NoteBatchBuilder(properties()));
        VideoContext context = new VideoContext("source", VideoNoteProfile.GOAL, List.of(
                new VideoContext.VideoSegment(0, 10, "single", List.of(), List.of())));

        service.run(8L, context, null);

        verify(deepSeek, never()).aggregate(any(), any(), anyList(), anyString());
        verify(deepSeek).critique(any(), any(), any(), anyString());
    }

    private static AnalysisResult result(String prefix) {
        return new AnalysisResult(prefix, List.of(prefix), List.of(
                new AnalysisResult.Evidence(1, "CC", prefix, prefix)), List.of(), List.of());
    }

    private static NoteBatchProperties properties() {
        NoteBatchProperties p = new NoteBatchProperties();
        p.setModelContextTokens(256);
        p.setBatchSoftInputTokens(10);
        p.setBatchHardInputTokens(20);
        p.setOutputReserveTokens(0);
        p.setSafetyReserveTokens(0);
        p.setSegmentPartMaxChars(100);
        p.setCharsPerToken(1D);
        return p;
    }
}
