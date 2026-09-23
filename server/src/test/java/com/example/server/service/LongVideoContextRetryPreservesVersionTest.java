package com.example.server.service;

import com.example.server.config.AgentBudgetProperties;
import com.example.server.dto.AgentState;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Critic 重试上下文必须保留 V2 元数据（真实缺陷回归，2026-09-20）：
 * 丢失 analysisVersion 会让第二轮把版本匹配失败的分块整批重建/重检索，白白烧掉预算。
 */
class LongVideoContextRetryPreservesVersionTest {

    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final MediaIndexService mediaIndexService = mock(MediaIndexService.class);
    private final VideoEvidenceRetrievalService retrievalService = mock(VideoEvidenceRetrievalService.class);
    private final AgentBudgetProperties budgetProperties = new AgentBudgetProperties();

    private final LongVideoContextService service =
            new LongVideoContextService(telemetry, mediaIndexService, retrievalService, budgetProperties);

    private static VideoContext.VideoSegment seg(long startMs, long endMs) {
        return new VideoContext.VideoSegment(startMs, endMs, "文本", List.of(), List.of());
    }

    @Test
    void critiqueRetryContextPreservesV2Metadata() {
        VideoContext full = new VideoContext("http://minio/s.mp4", "goal",
                List.of(seg(0, 400_000)), 1_800_000L, VideoContext.ANALYSIS_VERSION_V2,
                List.of(new VideoChapter("vp-0-0", "开场", 0, 300_000, 1, "BILIBILI_VIEW_POINT")));
        when(mediaIndexService.ensureChunks(eq(9L), any(VideoContext.class)))
                .thenReturn(new MediaIndexService.ChunkSnapshot(List.<VideoChunk>of(), true));
        when(retrievalService.retrieve(any(), any(), any(), any()))
                .thenReturn(List.of(seg(0, 400_000)));
        AgentState.CriticResult critique = new AgentState.CriticResult(
                false, List.of("补证据"), List.of("缺X"), List.of(), List.of(60_000L));

        service.refineForCritique(9L, full, full, critique);

        ArgumentCaptor<VideoContext> captor = ArgumentCaptor.forClass(VideoContext.class);
        verify(mediaIndexService).ensureChunks(eq(9L), captor.capture());
        assertEquals(VideoContext.ANALYSIS_VERSION_V2, captor.getValue().analysisVersion(),
                "重试上下文必须保留分析版本，否则分块版本匹配失败整批重建");
        assertEquals(1_800_000L, captor.getValue().durationMs());
        assertEquals(1, captor.getValue().chapters().size());
    }
}
