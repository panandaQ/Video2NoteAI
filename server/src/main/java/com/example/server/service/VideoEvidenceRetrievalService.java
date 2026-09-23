package com.example.server.service;

import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoEvidenceHit;
import com.example.server.dto.VideoRetrievalIntent;
import com.example.server.dto.knowledge.HistoryTurn;
import com.example.server.dto.knowledge.QueryPlan;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查询感知的视频证据检索：语义召回负责“说了什么”，OCR 通道负责“画面写了什么”。 */
@Service
public class VideoEvidenceRetrievalService {

    /**
     * 粗排（chunk 级）保留的候选数。
     *
     * <p>这是两级检索的召回上界：粗排决定哪些 chunk 能进入精排，精排只能在存活者内部排序，
     * 所以粗排一旦截断，gold 再相关也救不回来。原值 3 对单视频过于激进——video 66 有 21 个
     * chunk，只留 3 个（14%），实测 gold 的 chunk 排第 4 就直接出局（Recall@5 因此掉了 2 处）。
     *
     * <p>单视频的 chunk 量级是数十个（实测 6/21/15），取 20 使单视频场景下几乎不截断；同时
     * <b>保留两级结构</b>——跨库检索（LIBRARY）落地时语料会到全库量级，那时候选集收缩必须
     * 存在，提前把这层留着，届时只需按规模调这个数。
     */
    private static final int COARSE_TOP_K = 20;

    /**
     * Qdrant 向量检索返回的 chunk 数上限（粗排语义分数的来源）。
     *
     * <p>与 {@link #COARSE_TOP_K} 分开：它控制的是「取多少个远程向量分数」，不是候选集截断——
     * 未命中的 chunk 会回退到本地余弦，仍有语义分数。两者混用同一个常数会让「放宽粗排」
     * 顺带改掉向量检索宽度，实验无法归因。
     */
    private static final int VECTOR_TOP_K = 6;

    /** 返回给调用方的片段数上限（问答侧 {@code knowledge.retrieval.max-evidence} 的上游）。 */
    private static final int MAX_USER_HITS = 8;

    private final DeepSeekUtils deepSeekUtils;
    private final EmbeddingUtils embeddingUtils;
    private final QdrantVectorStore vectorStore;
    private final AgentTelemetry telemetry;

    public VideoEvidenceRetrievalService(DeepSeekUtils deepSeekUtils,
                                         EmbeddingUtils embeddingUtils,
                                         QdrantVectorStore vectorStore,
                                         AgentTelemetry telemetry) {
        this.deepSeekUtils = deepSeekUtils;
        this.embeddingUtils = embeddingUtils;
        this.vectorStore = vectorStore;
        this.telemetry = telemetry;
    }

    /**
     * 查询规划（D-111）：一次模型调用产出消歧问法与检索意图。
     *
     * <p>模型不可用、或产出缺少 semanticQuery 时退化为「原问题 + 按标点切词」的规则版
     * （与合并前 {@code retrievalIntent} 的兜底口径一致）。规则版够不够用是可测的——
     * 这层兜底的存在让那次调用的边际收益可以被实测，而不是只能靠信念。
     */
    public QueryPlan planQuery(String question, List<HistoryTurn> history) {
        try {
            QueryPlan plan = deepSeekUtils.planQuery(question, history);
            if (plan.usable()) {
                return plan;
            }
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("queryPlanFallbacks", 1);
        }
        return QueryPlan.fallback(question);
    }

    public List<VideoContext.VideoSegment> retrieve(Long mediaId,
                                                     String goal,
                                                     List<VideoChunk> chunks) {
        return retrieve(mediaId, goal, chunks, null);
    }

    /** 逐章检索（计划 §5.3）：{@code chapterId} 非空时 Qdrant 侧只搜该章的点。 */
    public List<VideoContext.VideoSegment> retrieve(Long mediaId,
                                                     String goal,
                                                     List<VideoChunk> chunks,
                                                     String chapterId) {
        return rank(mediaId, planQuery(goal, List.of()).toRetrievalIntent(), chunks, chapterId).stream()
                .map(ScoredSegment::segment)
                .toList();
    }

    /**
     * 检索入口：查询规划由调用方持有并复用。
     *
     * <p>检索与回答共用同一次查询理解——把规划结果作为入参而不是在方法内自己再调一次模型，
     * 既是减少调用，也保证「回答针对的问题」与「检索用的问题」不会各算各的。
     */
    public List<VideoEvidenceHit> search(Long mediaId,
                                         QueryPlan plan,
                                         List<VideoChunk> chunks) {
        return rank(mediaId, plan.toRetrievalIntent(), chunks, null).stream()
                .limit(MAX_USER_HITS)
                .map(this::toHit)
                .toList();
    }

    /** 兼容旧调用方（长视频裁剪、逐章检索）：内部按原问题规划一次，无历史参与。 */
    public List<VideoEvidenceHit> search(Long mediaId,
                                         String query,
                                         List<VideoChunk> chunks) {
        return search(mediaId, planQuery(query, List.of()), chunks);
    }

    private List<ScoredSegment> rank(Long mediaId,
                                     VideoRetrievalIntent intent,
                                     List<VideoChunk> chunks,
                                     String chapterId) {
        List<Double> queryEmbedding = embed(intent.semanticQuery());
        Map<String, Double> vectorScores = vectorScores(mediaId, queryEmbedding, chapterId);

        List<ScoredChunk> rankedChunks = chunks.stream()
                .map(chunk -> new ScoredChunk(
                        chunk, score(intent, queryEmbedding, vectorScores, chunk)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(COARSE_TOP_K)
                .toList();
        if (!rankedChunks.isEmpty()) {
            telemetry.valueCurrent("retrievalTopScore",
                    rankedChunks.get(0).score());
            telemetry.incrementCurrent("retrievalChunks", rankedChunks.size());
        }
        return rankedChunks.stream()
                .flatMap(chunk -> chunk.chunk().rawSegments().stream()
                        .map(segment -> scoreSegment(intent, chunk.score(), segment)))
                .sorted(Comparator.comparingDouble(ScoredSegment::score).reversed()
                        .thenComparingLong(result -> result.segment().startMs()))
                .toList();
    }

    public void index(Long mediaId, List<VideoChunk> chunks) {
        try {
            vectorStore.upsert(mediaId, chunks);
            telemetry.incrementCurrent("vectorStoreWrites", chunks.size());
        } catch (RuntimeException e) {
            // 向量库挂了仍可走内存向量和关键词，别让检索基础设施拖垮分析主链路。
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
    }

    private double score(VideoRetrievalIntent intent,
                         List<Double> queryEmbedding,
                         Map<String, Double> vectorScores,
                         VideoChunk chunk) {
        Double remoteScore = vectorScores.get(chunkKey(chunk));
        double semanticScore = remoteScore == null
                ? cosine(queryEmbedding, chunk.embedding())
                : remoteScore;
        return semanticScore * 0.6
                + termScore(intent.keywords(), searchableText(chunk)) * 0.25
                + termScore(intent.visualKeywords(), visualText(chunk)) * 0.15;
    }

    private Map<String, Double> vectorScores(Long mediaId, List<Double> queryEmbedding, String chapterId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (mediaId == null || queryEmbedding.isEmpty()) return scores;
        try {
            // V2 查询强制过滤处理版本，旧 ASR-only 点不参与打分（计划 §6.2）；逐章检索再限 chapterId
            vectorStore.search(mediaId, VideoContext.ANALYSIS_VERSION_V2, chapterId,
                            queryEmbedding, VECTOR_TOP_K)
                    .forEach(hit -> scores.put(hit.startMs() + ":" + hit.endMs(), hit.score()));
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("vectorStoreFallbacks", 1);
        }
        return scores;
    }

    private ScoredSegment scoreSegment(VideoRetrievalIntent intent,
                                       double chunkScore,
                                       VideoContext.VideoSegment segment) {
        double transcriptScore = termScore(intent.keywords(), segment.transcript());
        double visualScore = termScore(
                intent.visualKeywords(), String.join(" ", normalizedOcrTexts(segment)));
        return new ScoredSegment(
                segment,
                chunkScore * 0.55 + transcriptScore * 0.25 + visualScore * 0.20,
                transcriptScore,
                visualScore);
    }

    private VideoEvidenceHit toHit(ScoredSegment result) {
        VideoContext.VideoSegment segment = result.segment();
        List<String> ocrTexts = normalizedOcrTexts(segment);
        boolean hasTranscript = !segment.transcript().isBlank();
        boolean hasOcr = !ocrTexts.isEmpty();
        // 证据来源携带真实转录来源（CC/ASR），不再硬编码 ASR（计划 §4.5）
        String source = hasTranscript && hasOcr
                ? segment.source().name() + "+OCR"
                : hasOcr ? "OCR" : hasTranscript ? segment.source().name() : "时间片段";
        String ocrText = String.join(" ", ocrTexts);
        // 展示片段**转录优先**（D-008 字幕优先的延伸）：OCR 通道仍然参与打分与召回，
        // 但只要有转录就把它作为展示文本——画面文字得分更高时旧实现会选中 OCR 乱码
        // （实测 media 62：CC 字幕干净、OCR 把幻灯片识别成 "Estimating resolution as" 噪声，
        // 追问全部因证据不可读而拒答），OCR 只在转录缺失时充当展示文本。
        //
        // 注意（D-100）：这里只决定"展示/引用片段"选哪一路，**不再是喂给回答模型的唯一文本**。
        // 回答 prompt 由 EvidenceGroundedAnswerService 同时携带 transcript 与 visualText 两个通道，
        // 否则"答案只在画面文字里"的问题必然拒答（实测 media 66「作者都是谁」：8 个作者名只存在于
        // ocrTexts，口播只讲数量与机构）。同时**取消 180 字截断**：作者名单、表格、公式需要完整，
        // 截断会让证据在关键位置被切断。
        String preferred = hasTranscript ? segment.transcript() : ocrText;
        if (preferred.isBlank()) preferred = hasOcr ? ocrText : segment.transcript();
        if (preferred.isBlank()) preferred = "该时间段暂无可展示文本";
        return new VideoEvidenceHit(
                segment.startMs(),
                segment.endMs(),
                source,
                collapseWhitespace(preferred),
                segment.transcript(),
                ocrTexts,
                result.score());
    }

    private String searchableText(VideoChunk chunk) {
        return String.join(" ",
                chunk.segmentSummary(),
                String.join(" ", chunk.keywords()),
                String.join(" ", chunk.originalTerms()),
                chunk.rawSegments().stream()
                        .map(VideoContext.VideoSegment::transcript)
                        .collect(java.util.stream.Collectors.joining(" ")));
    }

    private String visualText(VideoChunk chunk) {
        return chunk.rawSegments().stream()
                .flatMap(segment -> normalizedOcrTexts(segment).stream())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private List<String> normalizedOcrTexts(VideoContext.VideoSegment segment) {
        return segment.ocrTexts().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .distinct()
                .toList();
    }

    private double termScore(List<String> terms, String content) {
        String normalizedContent = normalize(content);
        List<String> normalizedTerms = terms.stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .distinct()
                .toList();
        long matched = normalizedTerms.stream().filter(normalizedContent::contains).count();
        return normalizedTerms.isEmpty() ? 0 : (double) matched / normalizedTerms.size();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) return 0;
        double dot = 0;
        double leftLength = 0;
        double rightLength = 0;
        for (int i = 0; i < left.size(); i++) {
            dot += left.get(i) * right.get(i);
            leftLength += left.get(i) * left.get(i);
            rightLength += right.get(i) * right.get(i);
        }
        if (leftLength == 0 || rightLength == 0) return 0;
        return dot / (Math.sqrt(leftLength) * Math.sqrt(rightLength));
    }

    private List<Double> embed(String text) {
        try {
            return embeddingUtils.embed(text);
        } catch (RuntimeException e) {
            telemetry.incrementCurrent("embeddingFallbacks", 1);
            return List.of();
        }
    }

    private String chunkKey(VideoChunk chunk) {
        return chunk.startTime() + ":" + chunk.endTime();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * 只折叠空白，**不截断**（D-100 取消 180 字上限）。
     *
     * <p>旧的 180 字截断会把作者名单、表格与公式在关键位置切断，让本可回答的问题变成"证据不足"。
     * 长度由"命中数 × 单片段文本量"自然约束：片段按 60 秒边界切分，实测转录 ≤ ~600 字、
     * 单条 OCR ≤ ~2,000 字，8 条命中的上限完全在模型上下文预算内。
     */
    private String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private record ScoredChunk(VideoChunk chunk, double score) {
    }

    private record ScoredSegment(
            VideoContext.VideoSegment segment,
            double score,
            double transcriptScore,
            double visualScore
    ) {
    }
}
