package com.example.server.service;

import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 证据展示片段的通道选择契约（D-008 字幕优先的延伸）。
 *
 * <p>真实链路教训（media 62）：画面文字得分更高时旧实现把 OCR 乱码当展示片段，
 * CC 转录虽然存在且干净，模型看到的却全是噪声，追问全部拒答。现在展示片段转录优先：
 * OCR 通道仍参与打分与召回，只在转录缺失时充当展示文本。
 */
class VideoEvidenceRetrievalServiceTest {

    private final DeepSeekUtils deepSeekUtils = mock(DeepSeekUtils.class);
    private final EmbeddingUtils embeddingUtils = mock(EmbeddingUtils.class);
    private final QdrantVectorStore vectorStore = mock(QdrantVectorStore.class);
    private final AgentTelemetry telemetry = mock(AgentTelemetry.class);
    private final VideoEvidenceRetrievalService service =
            new VideoEvidenceRetrievalService(deepSeekUtils, embeddingUtils, vectorStore, telemetry);

    @Test
    void searchPrefersTranscriptForDisplayEvenWhenOcrScoresHigher() {
        // 降级到本地打分：检索意图、Embedding、向量库全部不可用。
        when(deepSeekUtils.planQuery(anyString(), anyList())).thenThrow(new IllegalStateException("no model"));
        when(embeddingUtils.embed(any())).thenThrow(new IllegalStateException("no embedding"));

        VideoContext.VideoSegment segment = new VideoContext.VideoSegment(
                10_000, 20_000,
                "这一段讲的是香港新派武侠的起源",
                List.of("现代武侠新载体 Estimating resolution as"), // OCR 命中关键词、得分更高，但它是噪声
                List.of(), TranscriptSource.CC, null);
        VideoChunk chunk = new VideoChunk(0, 60_000, "", List.of(), List.of(segment), List.of());

        List<VideoEvidenceHit> hits = service.search(27L, "现代武侠新载体", List.of(chunk));

        assertEquals(1, hits.size());
        assertEquals("这一段讲的是香港新派武侠的起源", hits.get(0).snippet());
        assertEquals("CC+OCR", hits.get(0).source());
    }

    @Test
    void searchFallsBackToOcrOnlyWhenTranscriptMissing() {
        when(deepSeekUtils.planQuery(anyString(), anyList())).thenThrow(new IllegalStateException("no model"));
        when(embeddingUtils.embed(any())).thenThrow(new IllegalStateException("no embedding"));

        VideoContext.VideoSegment segment = new VideoContext.VideoSegment(
                10_000, 20_000, "", List.of("PPT 标题文字"), List.of(), TranscriptSource.CC, null);
        VideoChunk chunk = new VideoChunk(0, 60_000, "", List.of(), List.of(segment), List.of());

        List<VideoEvidenceHit> hits = service.search(27L, "PPT 标题文字", List.of(chunk));

        assertEquals(1, hits.size());
        assertEquals("PPT 标题文字", hits.get(0).snippet());
        assertEquals("OCR", hits.get(0).source());
    }

    /** D-100：展示片段取消 180 字截断（作者名单/表格/公式被切断会让可答问题变成"证据不足"）。 */
    @Test
    void displaySnippetIsNotTruncated() {
        when(deepSeekUtils.planQuery(anyString(), anyList())).thenThrow(new IllegalStateException("no model"));
        when(embeddingUtils.embed(any())).thenThrow(new IllegalStateException("no embedding"));

        String longTranscript = "作者名单与贡献说明".repeat(40); // 320 字
        VideoContext.VideoSegment segment = new VideoContext.VideoSegment(
                10_000, 20_000, longTranscript, List.of(), List.of(), TranscriptSource.CC, null);
        VideoChunk chunk = new VideoChunk(0, 60_000, "", List.of(), List.of(segment), List.of());

        List<VideoEvidenceHit> hits = service.search(27L, "作者名单", List.of(chunk));

        assertEquals(1, hits.size());
        assertEquals(longTranscript, hits.get(0).snippet());
        assertEquals(longTranscript, hits.get(0).transcript());
    }

    /**
     * D-110 粗排候选集必须覆盖全部 chunk：粗排决定召回上界，被截断的 chunk 里的片段
     * 无论多相关都进不了精排。
     *
     * <p>回归真实现场：原 {@code TOP_K = 3} 对单视频过于激进——video 66 有 21 个 chunk，
     * 只留 3 个（14%），gold 落在「相关工作」章（第 5 个 chunk）时整段出局，评价里的
     * Recall@5 因此少了一段；把 chunk 放宽到 20 后单视频场景几乎不截断。
     */
    @Test
    void coarseRankingKeepsChunksBeyondTheOldTopThreeCutoff() {
        when(deepSeekUtils.planQuery(anyString(), anyList())).thenThrow(new IllegalStateException("no model"));
        when(embeddingUtils.embed(any())).thenThrow(new IllegalStateException("no embedding"));

        // 4 个 chunk 命中同一个关键词：粗排分数完全相同，只按输入顺序取前 N 个。
        List<VideoChunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            VideoContext.VideoSegment segment = new VideoContext.VideoSegment(
                    i * 60_000L, (i + 1) * 60_000L,
                    "第" + (i + 1) + "段 共同关键词", List.of(), List.of(), TranscriptSource.CC, null);
            chunks.add(new VideoChunk(i * 60_000L, (i + 1) * 60_000L, "", List.of(),
                    List.of(segment), List.of()));
        }

        List<VideoEvidenceHit> hits = service.search(27L, "共同关键词", chunks);

        assertTrue(hits.stream().anyMatch(hit -> hit.startMs() == 180_000L),
                "排在第 4 位的 chunk 的片段也必须能进精排，不能被粗排截断丢掉");
    }
}
