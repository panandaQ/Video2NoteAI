package com.example.server.service;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.AnalysisInputManifest;
import com.example.server.dto.TranscriptSegment;
import com.example.server.dto.TranscriptSource;
import com.example.server.dto.VideoChapter;
import com.example.server.dto.VideoContext;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.OcrUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 多模态上下文构建：转录分支【字幕优先，ASR 兜底】+ 关键帧 OCR 分支并行（计划 §4.5/§5.1）。
 *
 * <p>字幕分支：manifest 声明字幕可用 → 下载对象 → {@link SubtitleTranscriptParser} 解析 →
 * 质量门槛（覆盖率并集 / 最大空洞）通过 → source=CC；不通过或不可用 → 现有 ASR 分支（source=ASR）。
 * OCR 分支与 60 分钟总预算、单路挂掉带另一半继续的语义全部不变。
 *
 * <p>章节语义：manifest 声明 {@code chapters=PRESENT} 但对象缺失、损坏或最终 Context 少章时，
 * 构建失败并进入可重试链路，不得静默降级为无章节（交付约束 2）；60 秒聚合窗口在章节边界处拆段。
 */
@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);
    private static final String EVIDENCE_OBJECT_PREFIX = "evidence-frames";
    private static final long SEGMENT_MS = 60_000L;
    private static final long FALLBACK_FRAME_INTERVAL_MS = 30_000L;
    private static final Pattern PTS_TIME = Pattern.compile("pts_time:([0-9.]+)");

    private final SegmentedTranscriptionService transcriptionService;
    private final OcrUtils ocrUtils;
    private final MinioUtils minioUtils;
    private final ThreadPoolTaskExecutor asrExecutor;
    private final ThreadPoolTaskExecutor ocrExecutor;
    private final AgentTelemetry telemetry;
    private final VideoImportProperties properties;
    private final ObjectMapper objectMapper;

    public VideoContextService(SegmentedTranscriptionService transcriptionService,
                               OcrUtils ocrUtils,
                               MinioUtils minioUtils,
                               @Qualifier("asrExecutor") ThreadPoolTaskExecutor asrExecutor,
                               @Qualifier("ocrExecutor") ThreadPoolTaskExecutor ocrExecutor,
                               AgentTelemetry telemetry,
                               VideoImportProperties properties,
                               ObjectMapper objectMapper) {
        this.transcriptionService = transcriptionService;
        this.ocrUtils = ocrUtils;
        this.minioUtils = minioUtils;
        this.asrExecutor = asrExecutor;
        this.ocrExecutor = ocrExecutor;
        this.telemetry = telemetry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public VideoContext build(String videoPath, String userGoal) {
        return build(videoPath, userGoal, null, null, null);
    }

    public VideoContext build(String videoPath, String userGoal, String traceId) {
        return build(videoPath, userGoal, traceId, null, null);
    }

    /**
     * @param manifest   内容资产的 V2 manifest；{@code null} 表示无补充资产（旧媒体/匿名无章节），按 ASR 兜底
     * @param durationMs 视频时长；用于字幕质量门槛与章节越界校验
     */
    public VideoContext build(String videoPath, String userGoal, String traceId,
                              AnalysisInputManifest manifest, Long durationMs) {
        String readableVideoPath = minioUtils.readableSource(videoPath);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        List<String> uploadedEvidenceFrames = new CopyOnWriteArrayList<>();
        CountDownLatch branchesFinished = new CountDownLatch(2);
        boolean cleanupWorkDir = true;
        try {
            Files.createDirectories(workDir);
            List<VideoChapter> chapters = loadChapters(manifest);
            // 两条分支各跑各的，单路挂掉还能带着另一半信息继续往下走。
            Future<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
                    asrExecutor,
                    branchesFinished,
                    () -> resolveTranscripts(readableVideoPath, workDir, traceId, manifest, durationMs, chapters));
            Future<BranchResult<FramePart>> frameFuture = submitBranch(
                    ocrExecutor,
                    branchesFinished,
                    () -> extractKeyFrames(
                            readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames));
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
                BranchResult<TranscriptSegment> transcriptResult = awaitBranch(transcriptFuture, deadline);
                BranchResult<FramePart> frameResult = awaitBranch(frameFuture, deadline);
                if (transcriptResult.failed()
                        && transcriptResult.error() instanceof SubtitleLoginRequiredException) {
                    // 登录失效：字幕拿不到，不降级 ASR，取消 OCR 分支并失败，让用户更新 Cookie 后重试。
                    cleanupWorkDir = cancelBranches(branchesFinished, frameFuture);
                    throw (SubtitleLoginRequiredException) transcriptResult.error();
                }
                return finishContext(
                        videoPath, userGoal, traceId, transcriptResult, frameResult,
                        uploadedEvidenceFrames, chapters, durationMs);
            } catch (TimeoutException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
            } catch (InterruptedException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VideoContext 构建被中断", e);
            } catch (ExecutionException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支执行失败", e.getCause());
            }
        } catch (Exception e) {
            deleteEvidenceFrames(uploadedEvidenceFrames);
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            if (cleanupWorkDir) {
                deleteDirectory(workDir);
            } else {
                log.warn("video_context_workdir_retained path={} reason=branch_still_running", workDir);
            }
        }
    }

    /**
     * 加载 View 章节（计划 §4.5 交付约束 2）：PRESENT 但对象缺失/损坏/为空 → 抛可重试失败，
     * 禁止静默进入无章节分析；ABSENT/INVALID → 无章节。
     */
    List<VideoChapter> loadChapters(AnalysisInputManifest manifest) {
        if (manifest == null || !AnalysisInputManifest.CHAPTER_PRESENT.equals(manifest.chapterStatus())) {
            return List.of();
        }
        try {
            byte[] bytes = minioUtils.readObjectBytes(manifest.chaptersObject());
            List<VideoChapter> chapters = objectMapper.readValue(bytes, new TypeReference<List<VideoChapter>>() { });
            if (chapters == null || chapters.isEmpty()) {
                throw new IllegalStateException("chapters.json 为空");
            }
            return chapters;
        } catch (Exception e) {
            throw new IllegalStateException("manifest 声明有章节但章节对象缺失或损坏，禁止降级为无章节", e);
        }
    }

    /**
     * 转录分支：字幕优先，ASR 兜底（计划 §4.5）。
     *
     * <p>字幕覆盖率达到阈值且最大空洞不超标才用字幕；否则弃用并回退 ASR，
     * telemetry 计数 {@code subtitleCoverageRejected}。ASR 分片边界 = 60 秒 ∪ 章节边界。
     */
    private List<TranscriptSegment> resolveTranscripts(String readableVideoPath,
                                                       Path workDir,
                                                       String traceId,
                                                       AnalysisInputManifest manifest,
                                                       Long durationMs,
                                                       List<VideoChapter> chapters) throws Exception {
        if (manifest != null && properties.isSubtitleEnabled()) {
            String subtitleStatus = manifest.subtitleStatus();
            // 登录态失效是"拿不到字幕"而不是"视频无字幕"：不降级 ASR，直接失败让用户更新 Cookie。
            if (AnalysisInputManifest.SUBTITLE_NEED_LOGIN.equals(subtitleStatus)) {
                throw new SubtitleLoginRequiredException(manifest.contentHash());
            }
            if (AnalysisInputManifest.SUBTITLE_AVAILABLE.equals(subtitleStatus)) {
                SubtitleTranscriptParser.ParsedSubtitle parsed = parseSubtitle(manifest, durationMs);
                if (parsed != null && subtitleGatePassed(parsed, properties)) {
                    telemetry.increment(traceId, "subtitleHits", 1);
                    telemetry.valueCurrent("subtitleCoverage", parsed.coverage());
                    telemetry.valueCurrent("subtitleMaxGapMs", parsed.maxGapMs());
                    log.info("video_context_subtitle_used coverage={} maxGapMs={}",
                            parsed.coverage(), parsed.maxGapMs());
                    return parsed.segments();
                }
                if (parsed != null) {
                    telemetry.increment(traceId, "subtitleCoverageRejected", 1);
                    log.warn("video_context_subtitle_rejected coverage={} maxGapMs={} minCoverage={} maxGapSeconds={}",
                            parsed.coverage(), parsed.maxGapMs(),
                            properties.getSubtitleMinCoverage(), properties.getSubtitleMaxGapSeconds());
                }
            }
        }
        // ASR 兜底：60 秒边界 ∪ View 章节边界，从切片阶段保证一个 segment 不跨章（§5.1）
        return transcriptionService.transcribe(
                readableVideoPath, workDir.resolve("audio"), traceId,
                SegmentedTranscriptionService.audioSliceBoundaries(chapters, durationMs), durationMs);
    }

    /** 字幕质量门槛（计划 §4.5）：覆盖率（并集）≥ minCoverage 且最大空洞 ≤ maxGapSeconds。 */
    static boolean subtitleGatePassed(SubtitleTranscriptParser.ParsedSubtitle parsed,
                                      VideoImportProperties properties) {
        return parsed.coverage() >= properties.getSubtitleMinCoverage()
                && parsed.maxGapMs() <= properties.getSubtitleMaxGapSeconds() * 1000L;
    }

    /** @return 解析失败返回 {@code null}（回退 ASR），不向上抛 */
    private SubtitleTranscriptParser.ParsedSubtitle parseSubtitle(AnalysisInputManifest manifest,
                                                                  Long durationMs) {
        try {
            byte[] bytes = minioUtils.readObjectBytes(manifest.subtitleObject());
            return SubtitleTranscriptParser.parse(new String(bytes, StandardCharsets.UTF_8), durationMs);
        } catch (Exception e) {
            log.warn("video_context_subtitle_parse_failed", e);
            return null;
        }
    }

    public void deleteEvidenceFrames(VideoContext context) {
        if (context == null) return;
        deleteEvidenceFrames(context.segments().stream()
                .flatMap(segment -> segment.evidenceFrames().stream())
                .distinct()
                .toList());
    }

    private void deleteEvidenceFrames(List<String> frames) {
        frames.stream()
                .filter(frame -> minioUtils.isManagedFile(frame, EVIDENCE_OBJECT_PREFIX))
                .distinct()
                .forEach(frame -> {
                    try {
                        minioUtils.removeFile(frame);
                    } catch (RuntimeException e) {
                        log.warn("evidence_frame_cleanup_failed frame={}", frame, e);
                    }
                });
    }

    private VideoContext finishContext(String videoPath,
                                       String userGoal,
                                       String traceId,
                                       BranchResult<TranscriptSegment> transcriptResult,
                                       BranchResult<FramePart> frameResult,
                                       List<String> uploadedEvidenceFrames,
                                       List<VideoChapter> chapters,
                                       Long durationMs) {
        if (transcriptResult.failed() && frameResult.failed()) {
            IllegalStateException failure = new IllegalStateException(
                    "ASR 和 OCR 分支均失败", transcriptResult.error());
            failure.addSuppressed(frameResult.error());
            throw failure;
        }
        if (transcriptResult.failed()) {
            telemetry.increment(traceId, "asrBranchFailures", 1);
            log.warn("video_context_asr_branch_failed", transcriptResult.error());
        }
        if (frameResult.failed()) {
            telemetry.increment(traceId, "ocrBranchFailures", 1);
            log.warn("video_context_ocr_branch_failed", frameResult.error());
            deleteEvidenceFrames(uploadedEvidenceFrames);
            uploadedEvidenceFrames.clear();
        }
        List<VideoContext.VideoSegment> segments =
                merge(transcriptResult.items(), frameResult.items(), chapters, durationMs);
        if (segments.isEmpty()) throw new IllegalStateException("视频未解析出有效语音或画面文字");
        return new VideoContext(videoPath, userGoal, segments,
                durationMs, VideoContext.ANALYSIS_VERSION_V2, chapters);
    }

    private <T> Future<BranchResult<T>> submitBranch(
            ThreadPoolTaskExecutor executor,
            CountDownLatch branchesFinished,
            ThrowingSupplier<List<T>> work) {
        try {
            return executor.submit(() -> {
                try {
                    return BranchResult.success(work.get());
                } catch (Exception e) {
                    return BranchResult.failure(e);
                } finally {
                    branchesFinished.countDown();
                }
            });
        } catch (RuntimeException e) {
            branchesFinished.countDown();
            return CompletableFuture.completedFuture(BranchResult.failure(e));
        }
    }

    private <T> BranchResult<T> awaitBranch(Future<BranchResult<T>> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new TimeoutException("VideoContext 总时间预算已耗尽");
        return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private boolean cancelBranches(CountDownLatch branchesFinished, Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) future.cancel(true);
        }
        try {
            return branchesFinished.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<FramePart> extractKeyFrames(String videoPath,
                                             Path frameDir,
                                             String traceId,
                                             List<String> uploadedEvidenceFrames) throws Exception {
        Files.createDirectories(frameDir);
        List<Long> timestamps = new ArrayList<>();
        runCommand(List.of(
                "ffmpeg", "-y", "-i", videoPath,
                "-vf", "select=eq(n\\,0)+gt(scene\\,0.35)+gte(t-prev_selected_t\\,30),showinfo",
                "-vsync", "vfr",
                frameDir.resolve("frame_%06d.jpg").toString()
        ), timestamps);

        List<Path> frameFiles;
        try (var paths = Files.list(frameDir)) {
            frameFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }

        List<FramePart> result = new ArrayList<>();
        Long previousHash = null;
        int failedFrames = 0;
        for (int i = 0; i < frameFiles.size(); i++) {
            long imageHash = differenceHash(frameFiles.get(i).toFile());
            if (previousHash != null && Long.bitCount(previousHash ^ imageHash) <= 5) {
                continue;
            }
            previousHash = imageHash;
            long timestampMs = i < timestamps.size() ? timestamps.get(i) : i * FALLBACK_FRAME_INTERVAL_MS;
            String ocrText;
            try {
                telemetry.increment(traceId, "ocrCalls", 1);
                ocrText = ocrUtils.recognize(frameFiles.get(i).toFile());
            } catch (RuntimeException e) {
                failedFrames++;
                telemetry.increment(traceId, "ocrFrameFailures", 1);
                log.warn("ocr_frame_failed frame={} timestampMs={}",
                        frameFiles.get(i).getFileName(), timestampMs, e);
                continue;
            }
            String frameUrl;
            try {
                frameUrl = minioUtils.uploadLocalFile(
                        frameFiles.get(i).toFile(),
                        frameFiles.get(i).getFileName().toString(),
                        EVIDENCE_OBJECT_PREFIX);
                uploadedEvidenceFrames.add(frameUrl);
            } catch (Exception e) {
                telemetry.increment(traceId, "frameUploadFailures", 1);
                log.warn("evidence_frame_upload_failed frame={} timestampMs={}",
                        frameFiles.get(i).getFileName(), timestampMs, e);
                frameUrl = videoPath + "#timestampMs=" + timestampMs;
            }
            result.add(new FramePart(timestampMs, ocrText, frameUrl));
        }
        if (result.isEmpty() && failedFrames > 0) {
            throw new IllegalStateException("所有 OCR 关键帧均处理失败");
        }
        return result;
    }

    /**
     * 合并转录与 OCR 帧（计划 §4.5/§5.1）：切片 = 60 秒网格 ∪ 章节边界，
     * 章节边界落在窗口内部时拆段，一个 segment 不跨两个 View 章节；未分章素材 chapterId=null。
     */
    static List<VideoContext.VideoSegment> merge(List<TranscriptSegment> transcripts,
                                                 List<FramePart> frames,
                                                 List<VideoChapter> chapters,
                                                 Long durationMs) {
        List<Long> sliceStarts = sliceStarts(transcripts, frames, chapters, durationMs);
        List<VideoContext.VideoSegment> segments = new ArrayList<>();
        for (int i = 0; i < sliceStarts.size(); i++) {
            long start = sliceStarts.get(i);
            long end = i + 1 < sliceStarts.size() ? sliceStarts.get(i + 1) : sliceEnd(start, durationMs);
            SegmentBuilder builder = new SegmentBuilder(start, end);
            boolean hasCc = false;
            boolean hasAsr = false;
            for (TranscriptSegment transcript : transcripts) {
                // 重叠归属：跨章节边界的 cue 完整进入两侧切片（时间范围由切片保证不跨章），
                // 只按起点归属会让另一侧切片空掉、丢失该章证据。
                if (transcript.startMs() < end && transcript.endMs() > start) {
                    builder.transcripts.add(transcript.text());
                    hasCc |= transcript.source() == TranscriptSource.CC;
                    hasAsr |= transcript.source() == TranscriptSource.ASR;
                }
            }
            for (FramePart frame : frames) {
                if (frame.timestampMs() >= start && frame.timestampMs() < end) {
                    if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
                        builder.ocrTexts.add(frame.ocrText());
                    }
                    builder.evidenceFrames.add(frame.frameName());
                }
            }
            if (builder.transcripts.isEmpty() && builder.ocrTexts.isEmpty()) {
                continue;
            }
            builder.chapterId = chapterIdAt(chapters, start, end);
            builder.source = hasCc ? TranscriptSource.CC : hasAsr ? TranscriptSource.ASR : TranscriptSource.ASR;
            segments.add(builder.build());
        }
        return segments;
    }

    /** 切片起点 = {0} ∪ 60 秒网格 ∪ 章节边界（升序去重）；章节边界落在窗口内部时拆段。 */
    static List<Long> sliceStarts(List<TranscriptSegment> transcripts,
                                  List<FramePart> frames,
                                  List<VideoChapter> chapters,
                                  Long durationMs) {
        TreeSet<Long> starts = new TreeSet<>();
        long maxTs = 0;
        for (TranscriptSegment transcript : transcripts) {
            maxTs = Math.max(maxTs, transcript.endMs());
        }
        for (FramePart frame : frames) {
            maxTs = Math.max(maxTs, frame.timestampMs());
        }
        if (durationMs != null && durationMs > 0) {
            maxTs = Math.max(maxTs, durationMs);
        }
        starts.add(0L);
        for (long t = SEGMENT_MS; t <= maxTs; t += SEGMENT_MS) {
            starts.add(t);
        }
        if (chapters != null) {
            for (VideoChapter chapter : chapters) {
                if (chapter.startMs() > 0 && chapter.startMs() <= maxTs) starts.add(chapter.startMs());
                if (chapter.endMs() > 0 && chapter.endMs() < maxTs) starts.add(chapter.endMs());
            }
        }
        final long upperBound = maxTs;
        starts.removeIf(start -> start > upperBound);
        return List.copyOf(starts);
    }

    static long sliceEnd(long start, Long durationMs) {
        if (durationMs != null && durationMs > start) return durationMs;
        return start + SEGMENT_MS;
    }

    /** 切片中点落在章节 [startMs, endMs) 内则归属该章，否则为未分章素材。 */
    static String chapterIdAt(List<VideoChapter> chapters, long start, long end) {
        if (chapters == null || chapters.isEmpty()) return null;
        long midpoint = start + (end - start) / 2;
        for (VideoChapter chapter : chapters) {
            if (midpoint >= chapter.startMs() && midpoint < chapter.endMs()) {
                return chapter.id();
            }
        }
        return null;
    }

    private long differenceHash(File imageFile) throws Exception {
        BufferedImage source = ImageIO.read(imageFile);
        if (source == null) return 0;
        BufferedImage scaled = new BufferedImage(9, 8, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }

        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash <<= 1;
                if (scaled.getRGB(x, y) > scaled.getRGB(x + 1, y)) hash |= 1;
            }
        }
        return hash;
    }

    private void runCommand(List<String> command, List<Long> timestamps) throws Exception {
        Path logPath = Files.createTempFile("dovideo-ffmpeg-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logPath.toFile())
                    .start();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("FFmpeg 执行超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("FFmpeg 执行失败");
            if (timestamps != null) {
                try (Stream<String> lines = Files.lines(logPath)) {
                    lines.forEach(line -> appendTimestamp(line, timestamps));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(logPath);
        }
    }

    private void appendTimestamp(String line, List<Long> timestamps) {
        if (!line.contains("showinfo")) return;
        Matcher matcher = PTS_TIME.matcher(line);
        if (matcher.find()) {
            timestamps.add((long) (Double.parseDouble(matcher.group(1)) * 1000));
        }
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    /** 关键帧 OCR 结果（包级可见，供章节边界合并的契约测试使用）。 */
    static record FramePart(long timestampMs, String ocrText, String frameName) {
    }

    private record BranchResult<T>(List<T> items, Exception error) {
        private static <T> BranchResult<T> success(List<T> items) {
            return new BranchResult<>(items, null);
        }

        private static <T> BranchResult<T> failure(Exception error) {
            return new BranchResult<>(List.of(), error);
        }

        private boolean failed() {
            return error != null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static class SegmentBuilder {        private final long startMs;
        private final long endMs;
        private final List<String> transcripts = new ArrayList<>();
        private final List<String> ocrTexts = new ArrayList<>();
        private final List<String> evidenceFrames = new ArrayList<>();
        private TranscriptSource source = TranscriptSource.ASR;
        private String chapterId;

        private SegmentBuilder(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
        }

        private VideoContext.VideoSegment build() {
            return new VideoContext.VideoSegment(
                    startMs,
                    endMs,
                    String.join("\n", transcripts),
                    ocrTexts,
                    evidenceFrames,
                    source,
                    chapterId);
        }
    }
}
